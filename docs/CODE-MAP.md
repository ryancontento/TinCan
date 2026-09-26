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
a window and tray, file dialogs and the clipboard, an OS theme lookup, and
image decoding.

## Tracing one message

Typing a question and watching the answer arrive touches most of the codebase.
In order:

1. **The composer** captures the keystroke. `Enter` sends and `Ctrl+Enter` inserts
   a newline (or the reverse, as a setting) — the decision is a pure function so it can be tested without a UI
   ([ComposerKeys.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ComposerKeys.kt)).
2. **`ChatViewModel.send()`** creates the conversation if needed, writes the
   question to the database, and decides whether to send or queue
   ([ChatViewModel.kt:280](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L280)).
3. **`generate()`** resolves the system prompt from the conversation row, trims
   history to fit the context window, opens the assistant row, and collects the
   stream
   ([ChatViewModel.kt:508](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L508)).
4. **`RemoteOllamaBackend.chat()`** does the HTTP and turns NDJSON lines into
   typed events
   ([RemoteOllamaBackend.kt:98](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/RemoteOllamaBackend.kt#L98)).
5. **`StreamSink`**, private at the bottom of `ChatViewModel`, rations updates:
   the screen refreshes every 30ms, the database every 500ms. A crash loses a
   fraction of a second, not the reply.
6. **`finish()`** decides what the row ends up as — complete, incomplete, or
   deleted and the question requeued
   ([ChatViewModel.kt:592](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L592)).

## Features, and where to start reading

### Chat and streaming

| | |
|---|---|
| Send / stop / stream | [ChatViewModel.kt:280](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L280) |
| Ollama HTTP and NDJSON | [RemoteOllamaBackend.kt](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/RemoteOllamaBackend.kt) |
| The interface everything talks to | [LlmBackend.kt:12](../llm-api/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/LlmBackend.kt#L12) |

`ChatEvent` and `LlmError` are the whole contract between UI and network
([LlmBackend.kt:59](../llm-api/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/LlmBackend.kt#L59)).
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
([ChatViewModel.kt:336](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L336)).

### Per-conversation prompt, model, temperature and context window

A conversation snapshots the default prompt when created, so editing the
default never rewrites threads already under way. Temperature and `num_ctx`
work the other way: blank follows Settings, a value overrides it. The rules
live on their own
([ConversationConfig.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConversationConfig.kt));
the editor is
[ConversationSettingsDialog.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConversationSettingsDialog.kt).

### Images and attached files

One Attach button, sorted by content rather than name
([Attachments.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/attach/Attachments.kt)):
images wait in the composer as thumbnails; text files go into the message as a
fenced block, so what is sent is visible and editable; anything else is
refused with a reason. `Ctrl+V` takes an image from the clipboard before a
text paste gets the chance. The dialog and clipboard are the platform half
([DesktopFilePicker.kt](../app/src/desktopMain/kotlin/io/github/ryancontento/tincan/attach/DesktopFilePicker.kt)).

Images are stored as blobs in their own table, keyed to the message and
cascading with it
([Entities.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/Entities.kt)).
Ollama keeps no state, so `historyFor()` re-attaches every earlier image on
every turn, and the context estimate charges each one `IMAGE_TOKENS`. Sending
to a model whose capabilities omit `vision` is refused before anything is
written ([ChatViewModel.kt:280](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L280)).

### Saved servers

A named list beside the one current address
([SavedServers.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/SavedServers.kt)),
stored as JSON in DataStore. The address in the top bar is the switcher; the
list is managed in Settings. Switching is refused mid-reply
([ChatViewModel.kt:247](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L247)).

### Model manager

Loaded models, unload, pull with progress, and delete, over `/api/ps`,
`/api/generate` with `keep_alive: 0`, `/api/pull` and `/api/delete`
([RemoteOllamaBackend.kt:207](../llm-ollama/src/commonMain/kotlin/io/github/ryancontento/tincan/llm/ollama/RemoteOllamaBackend.kt#L207)).
A failed pull still answers 200 and puts the error in the stream, which is
why pull failure is read per line. Screen and state are in
[models/](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/models/).

### Sidebar: pinning and date groups

Pinned first, then Today, Yesterday and so on by calendar day in the user's
time zone
([ConversationGroups.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ConversationGroups.kt)).
Pinning leaves `updatedAt` alone, so unpinning puts a conversation back where
its age says it belongs.

### Search

`LIKE` over the messages table, not FTS — instant at one person's history and
it costs no schema version
([ChatDao.kt:136](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/ChatDao.kt#L136)).
Wildcards in the term are escaped in
[ChatRepository.kt:218](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L218).
Snippet extraction and match highlighting are pure
([SearchSnippet.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/SearchSnippet.kt)).

### Regenerate and edit-and-resend

Both rewind the transcript before asking again, so the model never sees the
turns being replaced
([ChatViewModel.kt:372](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L372)
and
[:403](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/chat/ChatViewModel.kt#L403)).

Regenerate deletes the old reply *before* the request, which puts it at risk
for the length of the call — so a failed retry restores it
([ChatRepository.kt:200](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L200)).
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
([ChatRepository.kt:50](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/ChatRepository.kt#L50)).

### The look

All controls go through one file so density stays consistent
([Controls.kt](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Controls.kt)):
`TinField`, `TinButton`, `TinToolbarButton`, `TinIconButton`, `TinSegmented`,
`TinFormRow`, `TinSectionLabel`, `TinDivider`. Sizes are in `Metrics`
([:52](../app/src/commonMain/kotlin/io/github/ryancontento/tincan/ui/Controls.kt#L52)).

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
migration by diffing against the previous one. v1 to v2 is an automatic
migration, and
[MigrationTest](../data/src/desktopTest/kotlin/io/github/ryancontento/tincan/data/MigrationTest.kt)
builds a real v1 file from the committed schema to prove it.

Every connection runs with `secure_delete` on, and deleting a conversation
also empties the write-ahead log, so deleted text does not linger in the file
([TinCanDatabase.kt](../data/src/commonMain/kotlin/io/github/ryancontento/tincan/data/db/TinCanDatabase.kt)).

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
  splitting, fence parsing, error-to-notice mapping, context planning,
  attachment sorting, date grouping.
- **`desktopTest`** — anything touching Room, DataStore or timing, plus one
  Compose UI test that a streaming reply renders
  ([StreamingRenderTest](../app/src/desktopTest/kotlin/io/github/ryancontento/tincan/chat/StreamingRenderTest.kt)).

Backend fixtures are captured from a real Ollama with `curl`, not written by
hand — the model-management ones caught `keep_alive: 0` being silently dropped
by the encoder, which would have made "unload" load the model instead.

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
| A schema change | Bump the version in `TinCanDatabase.kt`; additive changes can be an `AutoMigration`, anything else needs a written one. Extend `MigrationTest` |

The one rule worth repeating: nothing platform-specific may enter `llm-api` or
`llm-ollama`, and nothing that imports `java.*` may enter any `commonMain`.
