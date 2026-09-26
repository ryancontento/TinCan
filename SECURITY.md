# Security

## Reporting a vulnerability

Please report it privately through GitHub's
[private vulnerability reporting](https://github.com/ryancontento/TinCan/security/advisories/new)
rather than opening a public issue. Include the steps to reproduce it and the
version you saw it in.

This is a one-person project, so expect a reply within a couple of weeks rather
than hours.

## Scope

TinCan talks to whatever Ollama server you point it at, and Ollama has no
authentication of its own. Traffic to a server on another machine is plain HTTP
unless you put it behind something that encrypts it, such as Tailscale or a
reverse proxy with TLS. Treat the server you configure as trusted: it decides
what the model says.

In scope:

- Anything in a model's reply that can make TinCan act on your machine without
  you meaning it to — opening files, running programs, reaching other hosts
- Conversation data leaking out of the local database or an export
- The build and release pipeline
