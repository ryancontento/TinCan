# TinCan

A chat client for local LLMs. Point it at Ollama on `localhost` or at another
machine on your network, and talk to your own models.

Desktop first — Windows and Linux. Android is v2 on the same codebase.

## Status

Working and in daily use, not yet released. Conversations persist in SQLite and
survive a restart; a reply interrupted by the process dying comes back as
incomplete rather than lost; a message composed while the server is unreachable
is queued and sent by itself once it returns.

Still to do: installers on a GitHub release, and the Android target.

## What it does

- Streams replies token by token, with markdown and code blocks that render as
  they arrive
- Keeps history per conversation, searchable across every message
- Regenerate a reply, or edit a question and resend from that point
- Per-conversation model and system prompt, so changing the default does not
  rewrite threads already under way
- Export a conversation to Markdown or JSON
- Shows context-window usage, because Ollama drops old turns at `num_ctx`
  without telling the client
- Collapsible reasoning traces for models that emit them
- Light, dark, or follow the desktop

## Requirements

- JDK 21
- [Ollama](https://ollama.com) running somewhere reachable, with at least one
  model pulled

## Running

```bash
./gradlew :app:run
```

The server field defaults to `http://localhost:11434`. Change it to reach
another machine — no restart needed, the next request uses the new value. A
MagicDNS name travels better than a raw IP, since it survives the network
changing underneath you.

## Packaging

```bash
./gradlew :app:packageDistributionForCurrentOS
```

`jpackage` only builds for the OS it runs on: a Windows machine produces an MSI
and nothing else. Linux packages come from the CI matrix in
`.github/workflows/build.yml`.

Pushing a tag of the form `vX.Y.Z` runs that matrix and attaches the MSI, DEB
and RPM to a GitHub release. The tag has to match `packageVersion` in
`app/build.gradle.kts` — CI checks, because jpackage stamps that number into
the installer and would not otherwise notice the mismatch.

## Privacy

Conversations never leave your machine except to reach the server you configure.
There is no telemetry and no request logging. The database records which server
each message went to as an opaque, salted key rather than an address, so a
transcript that gets copied or attached to a bug report carries no network
information.

## Layout

```
app/          Compose UI and state — commonMain, plus a desktop entry point
data/         Room database and DataStore settings; the platform-aware module
llm-api/      The LlmBackend interface and domain types. commonMain only.
llm-ollama/   Ktor implementation against Ollama's native /api/chat. commonMain only.
```

`llm-api` and `llm-ollama` have no platform-specific source sets and must keep
it that way — that's what makes the Android version a port rather than a
rewrite. `.github/workflows/android-safety.yml` enforces it on every push.

[docs/CODE-MAP.md](docs/CODE-MAP.md) says where each feature lives, and traces
one message from keystroke to stored reply.

## Tests

```bash
./gradlew build
```

Runs the full suite. The view-model tests use real dispatchers and real SQLite
rather than a virtual clock: Room and DataStore do genuine file IO on
dispatchers a test scheduler does not own, so advancing it proves nothing.

## Licence

MIT — see [LICENSE](LICENSE).
