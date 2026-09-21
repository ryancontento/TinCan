# Code map

Where each feature actually lives, for coming back to this after time away.

If you only read one section, read [Tracing one message](#tracing-one-message)
— almost everything else hangs off that path.

## The shape of it

Four modules, and the dependency arrows only ever point one way:

```
app  ──▶  data  ──▶  llm-api  ◀──  llm-ollama
 │                                      ▲
 └──────────────────────────────────────┘
```

| Module | Holds | Knows about |
|---|---|---|
| `llm-api` | The `LlmBackend` interface and domain types. No implementation. | Nothing |
| `llm-ollama` | The Ktor client for Ollama's native `/api/chat`. | `llm-api` |
| `data` | Room database, DataStore settings. | `llm-api` |
| `app` | Compose UI, view models, DI, desktop entry point. | all three |

`llm-api` and `llm-ollama` are `commonMain` only — no `desktopMain`, ever.
That is the seam that makes the Android version a port instead of a rewrite,
and `.github/workflows/android-safety.yml` fails the build if a platform source
set appears in either.

Everything in `app/src/commonMain` is shared with Android too. Only
`app/src/desktopMain` is this-platform-only, and it is deliberately tiny:
a window, a file dialog, an OS theme lookup.

## Tracing one message

Typing a question and watching the answer arrive touches most of the codebase.
In order:

1. **The composer** captures the keystroke. `Enter` sends, `Ctrl+Enter` inserts
   a newline — the decision is a pure function so it can be tested without a UI
   ([ComposerKeys.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ComposerKeys.kt)).
2. **`ChatViewModel.send()`** creates the conversation if needed, writes the
   question to the database, and decides whether to send or queue
   ([ChatViewModel.kt:230](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L230)).
3. **`generate()`** resolves the system prompt from the conversation row, trims
   history to fit the context window, opens the assistant row, and collects the
   stream
   ([ChatViewModel.kt:408](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L408)).
4. **`RemoteOllamaBackend.chat()`** does the HTTP and turns NDJSON lines into
   typed events
   ([RemoteOllamaBackend.kt:91](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/RemoteOllamaBackend.kt#L91)).
5. **`StreamSink`**, private at the bottom of `ChatViewModel`, rations updates:
   the screen refreshes every 30ms, the database every 500ms. A crash loses a
   fraction of a second, not the reply.
6. **`finish()`** decides what the row ends up as — complete, incomplete, or
   deleted and the question requeued
   ([ChatViewModel.kt:490](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L490)).

## Features, and where to start reading

### Chat and streaming

| | |
|---|---|
| Send / stop / stream | [ChatViewModel.kt:230](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L230) |
| Ollama HTTP and NDJSON | [RemoteOllamaBackend.kt](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/RemoteOllamaBackend.kt) |
| The interface everything talks to | [LlmBackend.kt:12](../llm-api/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/LlmBackend.kt#L12) |

`ChatEvent` and `LlmError` are the whole contract between UI and network
([LlmBackend.kt:29](../llm-api/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/LlmBackend.kt#L29)).
Adding a second backend means implementing `LlmBackend` and nothing else.

### Markdown and code blocks

Streaming markdown cannot be re-parsed on every chunk without stuttering, so
the settled prefix is parsed once per paragraph and the tail is drawn as plain
text
([StreamingMarkdown.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/StreamingMarkdown.kt)).
Rendering and the copy button live in
[MessageContent.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/MessageContent.kt);
fence parsing is separate and pure
([FencedCode.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/FencedCode.kt)).

### The unreachable path

`ConnectionMonitor` owns probing, offline state and the reconnect poll
([ConnectionMonitor.kt:23](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConnectionMonitor.kt#L23)).
Errors become a sentence and at most one action in
[Notice.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/Notice.kt).
Which exception maps to which error is decided by class name, not message,
because a DNS miss reads differently on every OS
([ErrorMapping.kt](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/ErrorMapping.kt)).

A message composed while offline is stored `PENDING` and sent on reconnect —
start at `deliverQueued()`
([ChatViewModel.kt:255](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L255)).

### Per-conversation prompt and model

A conversation snapshots the default prompt when created, so editing the
default never rewrites threads already under way. The rule is three lines and
lives on its own
([ConversationConfig.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConversationConfig.kt));
the editor is
[ConversationSettingsDialog.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConversationSettingsDialog.kt).

### Search

`LIKE` over the messages table, not FTS — instant at one person's history and
it costs no schema version
([ChatDao.kt:120](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/ChatDao.kt#L120)).
Wildcards in the term are escaped in
[ChatRepository.kt:183](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L183).
Snippet extraction and match highlighting are pure
([SearchSnippet.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/SearchSnippet.kt)).

### Regenerate and edit-and-resend

Both rewind the transcript before asking again, so the model never sees the
turns being replaced
([ChatViewModel.kt:291](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L291)
and
[:322](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L322)).

Regenerate deletes the old reply *before* the request, which puts it at risk
for the length of the call — so a failed retry restores it
([ChatRepository.kt:165](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L165)).
Do not remove that without reading the test.

### Export

Serialisation is pure and platform-free
([ConversationExport.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/export/ConversationExport.kt)).
Markdown is the readable transcript and omits reasoning traces; JSON is the
complete record. Writing the file is the one part that cannot be shared, so it
sits behind [FileSaver](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/export/FileSaver.kt)
with an AWT implementation in `desktopMain`.

### Context window

Ollama silently drops old turns at `num_ctx`, so trimming happens here instead
([ContextWindow.kt:34](../llm-api/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ContextWindow.kt#L34)).
The system prompt is never dropped but is charged; the newest message is kept
even if oversized; a null budget means no trim and an explicit warning.

### Privacy of stored data

Message rows record an opaque salted key, never a server address
([ServerKey.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ServerKey.kt)).
A startup sweep converts rows written by older versions and then rebuilds the
file, because an `UPDATE` only supersedes the old bytes
([ChatRepository.kt:46](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L46)).

### The look

All controls go through one file so density stays consistent
([Controls.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Controls.kt)):
`TinField`, `TinButton`, `TinToolbarButton`, `TinIconButton`, `TinSegmented`,
`TinFormRow`, `TinSectionLabel`, `TinDivider`. Sizes are in `Metrics`
([:51](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Controls.kt#L51)).

Colours, type scale and shapes are in
[Theme.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Theme.kt);
icons are drawn as paths in
[Icons.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Icons.kt).
Theme choice resolves through
[SystemTheme.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/SystemTheme.kt),
whose desktop half reads the registry, `gsettings` or `defaults`.

### Persistence

| | |
|---|---|
| Tables | [Entities.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/Entities.kt) |
| Queries | [ChatDao.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/ChatDao.kt) |
| The API the app uses | [ChatRepository.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt) |
| Settings | [SettingsRepository.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/SettingsRepository.kt) |

Room and DataStore never leave this module — no consumer gets `androidx.room`
on its classpath, the same way Ktor never escapes `llm-ollama`. Schemas are
committed under `data/schemas/` from v1, because Room can only generate a
migration by diffing against the previous one.

### Startup

`main()` starts Koin before any window exists, because the saved window size
has to be read first and a second graph would mean a second DataStore over one
file ([Main.kt](../app/src/desktopMain/kotlin/io/github/ryancontento/tincan/Main.kt)).
Bindings are in [AppModule.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/di/AppModule.kt);
anything platform-specific goes through
[PlatformModule.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/di/PlatformModule.kt).

Before any of that, the process claims the data directory with a lock file and
exits if another copy already holds it
([SingleInstance.kt](../app/src/desktopMain/kotlin/io/github/ryancontento/tincan/SingleInstance.kt)).
Two copies over one directory is not cosmetic — Room locks the database file
and DataStore refuses a second instance over the same file. A lock rather than
a pid file, so a crashed copy leaves nothing behind to clean up.

## Where the tests are, and why they differ

- **`commonTest`** — pure logic, no clock and no IO: key handling, markdown
  splitting, fence parsing, error-to-notice mapping, context planning.
- **`desktopTest`** — anything touching Room, DataStore or timing.

View-model tests use **real dispatchers and real SQLite**, not `runTest`'s
virtual clock. Room and DataStore do genuine file IO on dispatchers a test
scheduler does not own, so advancing it proves nothing — an earlier attempt
produced five tests that all silently observed the untouched default state.

Three tests exist because of specific bugs and should not be deleted casually:

| Test | Guards |
|---|---|
| [AppModuleTest](../app/src/desktopTest/kotlin/io/github/ryancontento/tincan/di/AppModuleTest.kt) | A wiring mistake that crashed startup while 74 unit tests passed |
| [RewriteTranscriptTest](../app/src/desktopTest/kotlin/io/github/ryancontento/tincan/chat/RewriteTranscriptTest.kt) | The prompt snapshot, and a failed regenerate destroying the old reply |
| [NewConversationTest](../app/src/desktopTest/kotlin/io/github/ryancontento/tincan/chat/NewConversationTest.kt) | A late database emission silently undoing New conversation |

Run everything with `./gradlew build` — plain `build` really does execute the
whole suite, which is also all CI runs.

## Where to add things

| Adding | Goes in |
|---|---|
| A second backend (llama.cpp, LM Studio) | New module implementing `LlmBackend`; bind it in `AppModule` |
| A new setting | `Settings.kt`, `SettingsRepository.kt`, then a `TinFormRow` in `SettingsScreen` |
| A new control | `ui/Controls.kt` — not inline in a screen |
| Anything platform-specific | `expect` in `commonMain`, `actual` in `desktopMain`, bound via `PlatformModule` |
| A schema change | Bump the version in `TinCanDatabase.kt` and write the migration; the exported schema is the diff source |

The one rule worth repeating: nothing platform-specific may enter `llm-api` or
`llm-ollama`, and nothing that imports `java.*` may enter any `commonMain`.
