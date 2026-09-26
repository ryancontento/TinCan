# TinCan

A chat client for local LLMs. Point it at Ollama on `localhost` or at another
machine on your network, and talk to your own models.

Desktop first — Windows and Linux. Android is v2 on the same codebase.

## Status

Working and in daily use. Conversations persist in SQLite and survive a
restart; a reply interrupted by the process dying comes back as incomplete
rather than lost; a message composed while the server is unreachable is queued
and sent by itself once it returns.

Still to do: the first tagged release through the new pipeline, macOS
packaging, and the Android target.

## What it does

- Streams replies token by token, with markdown and code blocks that render as
  they arrive
- Keeps history per conversation, searchable across every message, with
  pinning and Today / Yesterday / older groups in the sidebar
- Regenerate a reply, or edit a question and resend from that point
- Per-conversation model, system prompt, temperature and context window, so
  changing the defaults does not rewrite threads already under way
- Images for vision models — attach, or paste with `Ctrl+V` — and text or code
  files dropped into a message as a code block
- Saved servers, switched from the top bar: localhost one minute, the machine
  with the GPU the next
- A model manager: see what is loaded and how much of it is on the GPU, unload,
  pull new models with progress, delete
- Export a conversation to Markdown or JSON
- Shows context-window usage, because Ollama drops old turns at `num_ctx`
  without telling the client
- Collapsible reasoning traces for models that emit them
- Light, dark, or follow the desktop; Enter or Ctrl+Enter to send; optionally
  hide to the system tray instead of quitting

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

[docs/REMOTE-SERVER.md](docs/REMOTE-SERVER.md) covers setting up Ollama on
another machine, on your home network or over Tailscale.

## Packaging

```bash
./gradlew :app:packageDistributionForCurrentOS
```

`jpackage` only builds for the OS it runs on: a Windows machine produces an MSI
and nothing else. Linux packages come from the CI matrix in
`.github/workflows/build.yml`.

Pushing a tag of the form `vX.Y.Z` runs that matrix and attaches the MSI, DEB,
RPM, a Linux tarball for other distributions, and a `SHA256SUMS` file to a
GitHub release. An AUR package built from the tarball is in
[packaging/aur/](packaging/aur/). The tag has to match `packageVersion` in
`app/build.gradle.kts` — CI checks, because jpackage stamps that number into
the installer and would not otherwise notice the mismatch.

## Privacy

Conversations never leave your machine except to reach the server you configure.
There is no telemetry and no request logging. The database records which server
each message went to as an opaque, salted key rather than an address, so a
transcript that gets copied or attached to a bug report carries no network
information. Deleting a conversation overwrites its text in the database file
rather than just marking it free.

Links in a model's reply open only if they are `http` or `https`; anything
else — local files, network shares, other apps' URL schemes — is ignored.

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
