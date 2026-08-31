# HavocMaintenance

Maintenance mode for **Paper / Purpur 1.21.x** (Java 21), with a small HTTP API
so a Discord bot can read the server's state and toggle maintenance remotely.

## What it does

- `/maintenance on|off|toggle` blocks new logins and kicks everyone who isn't exempt
- Rewrites the server list MOTD while maintenance is on
- A bypass whitelist (by name) on top of the `havocmaintenance.bypass` permission
- State survives restarts — the server won't quietly reopen after a reboot
- Exposes JSON over HTTP for your Discord bot to poll
- Optionally fires a Discord webhook the moment maintenance is toggled

---

## Building

You don't need Java installed locally. Push this folder to a GitHub repo and the
included workflow builds it for you.

1. Create a new GitHub repository and push the contents of this folder to it
   (make sure `.github/workflows/build.yml` comes along — it's a hidden folder,
   so `git add .` rather than dragging files in a file manager).
2. Open the **Actions** tab. The build starts automatically on push, or you can
   run it manually with **Build HavocMaintenance → Run workflow**.
3. When it's green, open the run and download **HavocMaintenance-jar** from the
   Artifacts section at the bottom. GitHub wraps it in a `.zip` — unzip it to get
   `HavocMaintenance-1.0.0.jar`.

Tagging a commit `v1.0.0` also publishes a GitHub Release with the jar attached,
which is handy if you'd rather `wget` it straight onto the server.

If you do have Maven locally: `mvn clean package`, jar lands in `target/`.

### Targeting a different 1.21.x

`pom.xml` compiles against `1.21.4-R0.1-SNAPSHOT`. That's deliberate — a jar
built against an older 1.21 API runs fine on newer 1.21 builds, but not the
other way round. Change the `<paper.version>` property if you specifically need
a newer API.

---

## Installing

1. Drop the jar in `plugins/` and restart. **Remove the old Maintenance plugin
   first** — two plugins both cancelling logins and rewriting the MOTD will
   fight each other.
2. Open `plugins/HavocMaintenance/config.yml`.
3. Set `api.token` to a long random string. The plugin refuses to start the API
   while it's still the placeholder. Generate one with:
   ```
   openssl rand -hex 32
   ```
4. Set `api.port`. **On Pterodactyl this must be a real allocated port**: go to
   your server's **Network** tab, add a secondary allocation, and use that port
   number. Any other number will fail to bind.
5. `/maintenance reload`.

---

## Commands

| Command | Description |
| --- | --- |
| `/maintenance on [reason]` | Enable maintenance, kick non-exempt players |
| `/maintenance off` | Disable maintenance |
| `/maintenance toggle` | Flip the current state |
| `/maintenance status` | Show state, reason, uptime, TPS |
| `/maintenance reason <text>` | Change the reason without toggling |
| `/maintenance whitelist add\|remove\|list [player]` | Manage the bypass whitelist |
| `/maintenance reload` | Reload config.yml and restart the API |

Aliases: `/maint`, `/mm`

## Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `havocmaintenance.admin` | op | Use `/maintenance` |
| `havocmaintenance.bypass` | op | Join while maintenance is on |

---

## HTTP API

Every request needs the token, sent as either header:

```
Authorization: Bearer YOUR_TOKEN
X-Api-Key: YOUR_TOKEN
```

### `GET /status`

```json
{
  "ok": true,
  "maintenance": false,
  "reason": "Scheduled maintenance",
  "since": 1756620000000,
  "server": {
    "name": "HavocSMP",
    "version": "1.21.4-R0.1-SNAPSHOT",
    "online": 7,
    "max": 100,
    "tps": 19.98,
    "mspt": 3.21,
    "uptime_seconds": 8140,
    "snapshot_age_ms": 340,
    "players": ["Alex", "Steve"]
  }
}
```

`since` is epoch **milliseconds** of the last state flip — divide by 1000 before
handing it to Discord's `<t:...:R>` timestamp format.

### `POST /maintenance`

```json
{ "enabled": true, "reason": "Updating plugins", "actor": "skyler (Discord)" }
```

`reason` and `actor` are optional. Send `{"toggle": true}` instead of `enabled`
to flip whatever the current state is. Responds with the same shape as
`/status`, plus `"changed": true|false`.

### `GET /whitelist` · `POST /whitelist`

```json
{ "action": "add", "player": "Notch" }
```

### `GET /ping`

Liveness check: `{"ok": true, "plugin": "HavocMaintenance", "api": 1}`

### Test it

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://YOUR_SERVER_IP:8123/status
```

---

## A note on security

The API is **plain HTTP** and the token is sent in a header, so anyone who can
watch the traffic between your bot and your server can read it — and anyone with
the token can close your server to players.

Practical advice:

- Use a long random token, and don't reuse it anywhere else.
- If your host has a firewall, restrict the API port to your bot's IP.
- If the bot and server are on the same machine or private network, set
  `api.bind: 127.0.0.1` and don't expose the port publicly at all.
- If the traffic crosses the public internet and you care, put it behind a
  reverse proxy (Caddy/nginx) with TLS and point the bot at `https://`.
- The token lives in `config.yml`. Don't paste that file into Discord, and don't
  commit it to a public repo.
