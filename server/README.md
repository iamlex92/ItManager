# NetAdmin server

FastAPI backend for the NetAdmin DCIM Android app: floor plans, devices,
ports, cabling, VLANs, alerts, config backups, and live port status over
WebSocket. API reference: [API.md](API.md), Swagger UI at `/docs`.

## Safety model

The only switches this will ever see are production ones, so:

- **`HAL_MODE=emulator` is the default.** Switches are emulated in memory;
  nothing leaves the server. Demo data is seeded on first start.
- **`HAL_MODE=real` is read-only by default.** The real driver is always
  wrapped in a write guard. Port mode/VLAN changes, shutdown, port bounce
  and `write memory` return 403 unless `ALLOW_DEVICE_WRITES=true`.
- **Every write needs `"confirm": true`** in the request body, and uplink
  ports additionally need `"confirm_uplink": true`. The app should show the
  commands from the `/preview` endpoint and ask for biometrics before sending.
- **Every write attempt is audited** in `script_audit_logs`, including the
  blocked ones.
- The real SSH/SNMP driver (`app/hal/real.py`) is a stub that returns 501:
  nothing can reach real equipment yet.

The server is meant for the plant network only (reach it over VPN from
outside); auth is a single shared `API_TOKEN`.

## Run with Docker

```sh
cd server
cp .env.example .env      # set API_TOKEN
docker compose up -d --build
curl http://localhost:8000/api/v1/health
```

The container runs migrations, seeds the demo floor if the database is empty
(`SEED_DEMO_DATA=true`), then starts uvicorn on port 8000.

## Run locally

```sh
cd server
python -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
export DATABASE_URL=postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin
export API_TOKEN=$(python -c "import secrets; print(secrets.token_urlsafe(32))")
alembic upgrade head
python -m app.seed            # --reset to wipe and reseed
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
| `DATABASE_URL` | `postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin` | |
| `HAL_MODE` | `emulator` | `emulator` or `real` |
| `ALLOW_DEVICE_WRITES` | `false` | only matters for `real` |
| `POLL_INTERVAL_SECONDS` | `10` | port status polling; `0` disables |
| `EMULATOR_FLAP_PROBABILITY` | `0.1` | chance per switch per poll that a port drops for a few polls |
| `EMULATOR_BOUNCE_DELAY_SECONDS` | `2` | |

## Demo data

One floor (`Административно-производственный корпус`, 2400×1200 px plan):
core Cisco C9300-48P, access Eltex MES2324P (24), Eltex MES2348P (48) and
Cisco 2960X-24PS (24) with 10G/1G uplinks; patch panels, wall sockets,
PCs for accounting, HR and the production floor, three printers (one low on
toner), cameras on PoE, servers in the server room, an access point on a
trunk port, and two open LINK_DOWN alerts.

## Layout

```
app/
  main.py          app factory, lifespan (DB, driver, poller)
  models.py        SQLAlchemy models (spec schema + is_online, last_seen_at, plan size)
  schemas.py       Pydantic request/response models
  api/             routers; guarded.py holds confirmation, uplink and audit checks
  hal/             switch drivers: emulator, write guard, real (stub), CLI command builder
  services/        port sync + alerts, cable tracing, WebSocket hub
  seed.py          demo data
alembic/           migrations
```
