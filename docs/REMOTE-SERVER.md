# Using Ollama on another machine

TinCan talks to one Ollama server at a time, and that server can be on another
machine. You might run the models on a desktop with a good GPU, or on a Mac with
plenty of memory, and chat from a laptop. This guide covers the server side, and
then how to reach it on your home network or from anywhere with Tailscale.

> **Ollama has no authentication.** Anything that can reach its port can use
> your models. Never forward port 11434 on your router, and read
> [Keeping it private](#keeping-it-private) before exposing it anywhere.

## 1. Make Ollama listen beyond localhost

By default Ollama only accepts connections from its own machine
(`127.0.0.1:11434`). Set `OLLAMA_HOST` to `0.0.0.0:11434` so it listens on
every network interface, then restart Ollama.

**macOS.** Recent versions of the Ollama app have a setting that exposes it to
the network. Try that first. If yours doesn't, run the following and then quit
and reopen Ollama:

```bash
launchctl setenv OLLAMA_HOST "0.0.0.0:11434"
```

`launchctl setenv` does not survive a reboot. To make it permanent, add a
LaunchAgent that runs the same command at login.

**Linux (the official install script sets up a systemd service).**

```bash
sudo systemctl edit ollama.service
```

Add the following lines, then save:

```ini
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
```

Then reload and restart it:

```bash
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

**Windows.** Quit Ollama from its tray icon. Open *Edit the system environment
variables*, add a user variable `OLLAMA_HOST` with the value `0.0.0.0:11434`,
then start Ollama again.

**Check it worked.** On the server, the port should be listening on all
addresses, not just `127.0.0.1`:

```bash
# macOS / Linux
lsof -iTCP:11434 -sTCP:LISTEN -n -P      # want *:11434
```

```powershell
# Windows
Get-NetTCPConnection -LocalPort 11434 -State Listen   # want 0.0.0.0
```

If the server has a firewall, allow incoming connections to Ollama or to
TCP port 11434.

## 2. Reach it on your home network

From the machine running TinCan, confirm the server answers:

```bash
curl http://<server-address>:11434/api/tags
```

Then in TinCan, open **Settings**, set **Ollama address** to
`http://<server-address>:11434`, and press **Test connection**.

A raw IP address changes whenever the router hands out a new one. Two steadier
options:

- A DHCP reservation on your router, so the server always gets the same IP.
- The server's mDNS name (`<hostname>.local`). macOS and Windows resolve these
  out of the box; on Linux, `avahi-daemon` must be running.

## 3. Reach it from anywhere with Tailscale

[Tailscale](https://tailscale.com) puts your devices on a private network that
works across the internet. Traffic between them is encrypted end to end, which
matters here because TinCan talks to Ollama over plain HTTP.

1. Install Tailscale on the server and on the machine running TinCan, and sign
   both into the same account.
2. Turn on **MagicDNS** in the Tailscale admin console. It usually is by default.
3. In TinCan, set the address to `http://<server-machine-name>:11434`. The
   short machine name works on your own devices; the full name,
   `<machine>.<your-tailnet>.ts.net`, works too.

A MagicDNS name is better than the server's Tailscale IP address: it keeps
working if you reinstall Tailscale, and it doesn't depend on which network you
happen to be on.

Section 1 is still needed. Tailscale gives the traffic a route to the server,
but Ollama still has to be listening on more than localhost to answer it.

## Keeping it private

Binding to `0.0.0.0` means every network the server joins can reach Ollama.
That's usually fine on a home network, but not on café or hotel Wi-Fi. Ways to
narrow it, from least to most effort:

- **Turn it off when you travel.** On a laptop, reset `OLLAMA_HOST` before you
  join a public network.
- **Bind to the Tailscale address only.** Set `OLLAMA_HOST` to the server's
  Tailscale IP, for example `100.101.102.103:11434`. Ollama is then reachable
  only over Tailscale, and not even from the server's own `localhost`. Tailscale
  has to be connected before Ollama starts, or Ollama fails to bind.
- **Restrict who can reach the port.** Tailscale's access controls can limit
  port 11434 to your own devices, which matters if you share your tailnet.
- **Put a reverse proxy in front.** Keep Ollama on localhost and run Caddy or
  nginx with authentication and TLS in front of it. TinCan does not send
  credentials today, so this suits browser clients more than TinCan.

## When TinCan can't connect

| TinCan says | Usually means |
|---|---|
| *Nothing answered at that address* | The server is asleep or off, the name doesn't resolve, a firewall is dropping the connection, or Tailscale is disconnected on one side. |
| *That machine answered, but Ollama is not running on that port* | Ollama isn't running, or it's still bound to `127.0.0.1`. Recheck section 1, and make sure Ollama was restarted after changing `OLLAMA_HOST`. |
| *That server does not have "…" pulled* | The server is fine; that model isn't on it. Pull it on the server, or pick another model. |

A sleeping laptop stops serving. On macOS, `caffeinate -s` keeps it awake while
it's plugged in.
