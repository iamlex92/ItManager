#!/bin/sh
set -e
alembic upgrade head
exec uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8000 --proxy-headers
