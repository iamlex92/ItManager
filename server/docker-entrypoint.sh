#!/bin/sh
set -e
alembic upgrade head
if [ "${SEED_DEMO_DATA:-false}" = "true" ]; then
    python -m app.seed --if-empty
fi
exec uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8000 --proxy-headers
