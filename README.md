# TinCan

A chat client for local LLMs. Point it at Ollama on `localhost` or at another
machine on the LAN, and talk to your own models.

Desktop first — Windows and Linux. Android is v2 on the same codebase.

## Status

M3. Conversations persist in SQLite, history survives restart, and a reply
interrupted by the process dying is recovered as incomplete rather than lost.
No markdown rendering yet (M4) — code blocks render as plain text.

## Requirements

- JDK 21
- [Ollama](https://ollama.com) running somewhere reachable, with at least one model pulled

## Running

```bash
./gradlew :app:run
```

The server field defaults to `http://localhost:11434`. Change it to reach
another machine — no restart needed, the next request uses the new value.

## Packaging

```bash
./gradlew :app:packageDistributionForCurrentOS
```

`jpackage` only builds for the OS it runs on: a Windows machine produces an MSI
and nothing else. Linux packages come from the CI matrix in
`.github/workflows/build.yml`.

## Layout

```
app/          Compose UI and state — commonMain, plus a desktop entry point
data/         Room database and DataStore settings; the platform-aware module
llm-api/      The LlmBackend interface and domain types. commonMain only.
llm-ollama/   Ktor implementation against Ollama's native /api/chat. commonMain only.
```

`llm-api` and `llm-ollama` have no platform-specific source sets and must keep
it that way — that's what makes the Android version a port rather than a
rewrite.
