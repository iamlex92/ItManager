# NetAdmin server

FastAPI backend for the NetAdmin DCIM Android app: floor plans, devices,
ports, cabling, VLANs, alerts, config backups, and live port status over
WebSocket. API reference: [API.md](API.md), Swagger UI at `/docs`.

## Safety model

The only switches this will ever see are production ones, so:

- **`HAL_MODE=emulator` is the default.** Switches are emulated in memory;
  nothing leaves the server. The database starts empty: the inventory is
  built with the network scanner, the map editor or the web console.
- **`HAL_MODE=real` talks to Cisco IOS and Eltex MES over SSH** (asyncssh, app/hal/real.py):
  port state, running-config, ping and traceroute from the switch. The SSH login is the device's
  stored credential or `SWITCH_CREDENTIAL`. It is read-only by default: the real driver is always
  wrapped in a write guard. Port mode/VLAN changes, shutdown, port bounce
  and `write memory` return 403 unless `ALLOW_DEVICE_WRITES=true`.
- **Every write needs `"confirm": true`** in the request body, and uplink
  ports additionally need `"confirm_uplink": true`. The app should show the
  commands from the `/preview` endpoint and ask for biometrics before sending.
- **Every write attempt is audited** in `script_audit_logs`, including the
  blocked ones.
- The real SSH driver for switches (`app/hal/real.py`) is a stub that
  returns 501: nothing can change real switches yet.
- **Runbooks are emulated.** AD password reset/unlock/enable, NTFS effective
  access, database backup, VM snapshot and service restart run against an
  in-memory environment; no AD or server credentials exist on the server.
  Critical runbooks need `"confirm": true`, and every run is audited.
- **Monitoring is read-only.** `MONITOR_MODE=real` sends only SNMP GET /
  GETNEXT to printers and one ICMP echo per device per round; the trap
  receiver only listens. `MONITOR_MODE=emulator` (the code default, used by tests) sends nothing and
  makes the figures up; the installers' config sets `MONITOR_MODE=real`.
- **Network discovery is read-only and manual.** A scan starts only from an
  explicit request with explicit private ranges (at most a /22 each, 2048
  addresses in total). It sends ICMP echo, TCP connects to common ports, reads
  the banners SSH/Telnet/HTTP offer anyway and SNMP GET/GETNEXT; no logins, no
  SET. In `DISCOVERY_MODE=emulator` it is answered from the inventory. Adding
  found devices to the inventory writes only to the database.

- **Secrets are encrypted at rest.** With `SECRET_KEY` set, device SNMP
  communities and stored credentials (`/credentials`) are Fernet-encrypted in
  the database, never returned by the API and never written to the audit log.
  Plaintext rows are encrypted at startup; a missing or wrong key stops the
  server instead of serving broken values.

The server is meant for the plant network only (reach it over VPN from
outside); auth is a single shared `API_TOKEN`.

## Run with Docker

```sh
cd server
cp .env.example .env      # set API_TOKEN
docker compose up -d --build
curl http://localhost:8000/api/v1/health
```

The container runs migrations, then starts uvicorn on port 8000. The database lives
in the `pgdata` volume; the `backup` service writes a `pg_dump` to `./backups`
every `BACKUP_INTERVAL_HOURS` (24) and keeps `BACKUP_KEEP_DAYS` (14) days.
Restore with `scripts/restore.sh backups/<file>.dump`.

Plant deployment step by step (in Russian): [DEPLOY.md](DEPLOY.md).

## Packages without Docker

`app/agent/` is the server as a standalone program, `netadmin-agent`, built with PyInstaller
(`packaging/netadmin-agent.spec`) and packed in CI (`.github/workflows/server-agent.yml`):

- `netadmin-agent_<version>_amd64.deb`: systemd service `netadmin` as user `netadmin`, the system
  PostgreSQL over the local socket (peer auth), settings in `/etc/netadmin/netadmin.env`
  (`packaging/debian/`).
- `NetAdmin-Server-Setup.exe` (Inno Setup): Windows service via WinSW, a bundled PostgreSQL 16 that
  the agent creates on first start, starts before the API and stops after it, on `127.0.0.1:54329` only;
  data in `%ProgramData%\NetAdmin` (`packaging/windows/`).

`netadmin-agent run` applies migrations, then serves the API; `info`, `backup`, `restore` and
`init-config` are for admins and installers. Both packages are installed on a clean runner and
checked over HTTP in CI; master publishes them to the prerelease `server-latest`.

## Run locally

```sh
cd server
python -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
export DATABASE_URL=postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin
export API_TOKEN=$(python -c "import secrets; print(secrets.token_urlsafe(32))")
alembic upgrade head
uvicorn app.main:app_factory --factory --reload
```

## Tests

Tests need a PostgreSQL database they may wipe:

```sh
export TEST_DATABASE_URL=postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin_test
pytest
```

## Configuration

| Variable | Default | |
|---|---|---|
| `API_TOKEN` | (required) | shared secret, at least 16 chars |
| `SECRET_KEY` | empty | Fernet key for secrets at rest: `openssl rand -base64 32 \| tr '+/' '-_'` |
| `DATABASE_URL` | `postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin` | |
| `HAL_MODE` | `emulator` | `emulator` or `real` |
| `ALLOW_DEVICE_WRITES` | `false` | only matters for `real` |
| `SWITCH_CREDENTIAL` | empty | stored SSH credential (by name) for switches without their own |
| `SWITCH_ENABLE_SECRET` | empty | `enable` password when it differs from the login password |
| `SWITCH_SSH_PORT` | `22` | |
| `SWITCH_KNOWN_HOSTS` | empty | known_hosts file to check switch host keys; empty accepts any |
| `POLL_INTERVAL_SECONDS` | `10` | port status polling; `0` disables |
| `EMULATOR_FLAP_PROBABILITY` | `0.1` | chance per switch per poll that a port drops for a few polls |
| `EMULATOR_BOUNCE_DELAY_SECONDS` | `2` | |
| `MONITOR_MODE` | `emulator` | `real`: SNMP printer polling + ICMP heartbeat |
| `PRINTER_POLL_INTERVAL_SECONDS` | `300` | `0` disables |
| `DISCOVERY_MODE` | `real` | network scan: `real` (read-only, started by an operator) or `emulator` |
| `DISCOVERY_STEP_DELAY_SECONDS` | `0.15` | emulated network scan: pause per 16 addresses |
| `HEARTBEAT_INTERVAL_SECONDS` | `15` | real mode only; HOST_DOWN alerts for switches, servers, printers, cameras, APs (not PCs) |
| `TRAP_LISTEN_PORT` | `0` (off) | UDP port for linkDown/linkUp traps, v1/v2c |
| `TRAP_COMMUNITY` | `public` | traps with another community are dropped |
| `TRAFFIC_INTERVAL_SECONDS` | `300` | port load sampling for the charts (SNMP counters in real mode); 0 disables |
| `TRAFFIC_KEEP_DAYS` | `7` | how long load samples are kept |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | empty | alerts to a Telegram chat; off unless both are set (DEPLOY.md, section 9) |
| `TELEGRAM_MIN_SEVERITY` | `WARNING` | lowest severity sent |
| `TELEGRAM_DELAY_SECONDS` | `60` | send only alerts still open after this delay |
| `TELEGRAM_NOTIFY_RESOLVED` | `true` | also send "resolved" for alerts that were sent |
| `TELEGRAM_MAX_PER_MINUTE` | `20` | rate limit; dropped ones are counted in the next message |
| `TELEGRAM_PROXY` | empty | HTTP(S) proxy to reach api.telegram.org |
| `BACKUP_DIR` | empty (off) | built-in `pg_dump` backups (the packages set it; Docker uses its `backup` service) |
| `BACKUP_INTERVAL_HOURS`, `BACKUP_KEEP_DAYS` | `24`, `14` | |
| `PG_BIN_DIR` | empty (PATH) | folder with `pg_dump`/`pg_restore` (and `initdb`/`pg_ctl` for the bundled database) |
| `SNMP_VERSION` | `2c` | `2c` uses each device's `snmp_community`; `3` uses the `SNMP_V3_*` settings |
| `SNMP_V3_USER`, `SNMP_V3_AUTH_KEY`, `SNMP_V3_PRIV_KEY` | | |
| `SNMP_V3_AUTH_PROTOCOL` / `SNMP_V3_PRIV_PROTOCOL` | `SHA` / `AES` | `MD5`, `SHA`, `SHA256` / `DES`, `AES` |

Trap-to-port mapping uses the interface name in the trap (`ifName` /
`ifDescr`, e.g. `GigabitEthernet1/0/5` or `gi1/0/5`) and falls back to
`ports.if_index`. The seeded `if_index` values are illustrative. To have
switches send traps, on Cisco: `snmp-server enable traps snmp linkdown linkup`
and `snmp-server host <server> version 2c <community>`; on Eltex MES:
`snmp-server server` plus `snmp-server host <server> traps version 2 <community>`.

## Test inventory

The tests load `tests/inventory.py` before each API test: one floor, core Cisco C9300-48P, access
Eltex MES2324P (24), Eltex MES2348P (48) and Cisco 2960X-24PS (24) with 10G/1G uplinks; patch panels,
wall sockets, PCs, printers, cameras on PoE, servers and an access point. It is never loaded into a
real database.

## Peripherals, topology, faceplates

- `/peripherals`: monitors, keyboards, docking stations and the like, attached to a device
  (`device_id`) or in stock (`device_id: null`). Deleting the device puts them back in stock.
- `/topology`: active devices and the cables between them, traced through sockets and patch panels,
  plus virtual machines tied to their hosts. Read from the inventory only.
- `/devices/{id}/faceplate`: an SVG of the device's front panel and where each port lies on it
  (fractions of the drawing). The app draws it in the rack view with ports coloured by link state.

## Layout

```
app/
  main.py          app factory, lifespan (DB, driver, poller)
  models.py        SQLAlchemy models (spec schema + is_online, last_seen_at, plan size)
  schemas.py       Pydantic request/response models
  api/             routers; guarded.py holds confirmation, uplink and audit checks
  hal/             switch drivers: emulator, write guard, real (stub), CLI command builder
  services/        port sync + alerts, cable tracing, WebSocket hub
  discovery/       network scan (emulator, ping + SNMP), import into the inventory
  runbooks/        runbook catalog and emulated environment
  agent/           standalone server for the Debian and Windows packages (CLI, bundled PostgreSQL)
alembic/           migrations
packaging/         PyInstaller spec, .deb scripts, Windows installer
```
