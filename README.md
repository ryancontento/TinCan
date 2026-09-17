# TinCan

A chat client for local LLMs. Point it at Ollama on `localhost` or at another
machine on the LAN, and talk to your own models.

Desktop first — Windows and Linux. Android is v2 on the same codebase.

## Status

M0/M1. The app opens a window, lists models from Ollama, and streams a reply.
No conversation history yet (that's M3), no markdown rendering (M4).

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
llm-api/      The LlmBackend interface and domain types. commonMain only.
llm-ollama/   Ktor implementation against Ollama's native /api/chat. commonMain only.
```

`llm-api` and `llm-ollama` have no platform-specific source sets and must keep
it that way — that's what makes the Android version a port rather than a
rewrite.
