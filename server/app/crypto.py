"""Encryption at rest for secrets in the database (SNMP communities, stored
credentials). Fernet (AES-128-CBC + HMAC-SHA256) with the SECRET_KEY from the
server's .env; the key never lives in the database or the repository.

Stored values look like "enc:v1:<token>". Rows written before a key was set
stay plaintext until startup re-encrypts them (see encrypt_existing)."""

import logging

from cryptography.fernet import Fernet, InvalidToken
from sqlalchemy import Text, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.types import TypeDecorator

log = logging.getLogger(__name__)

PREFIX = "enc:v1:"


class SecretsUnavailable(RuntimeError):
    """An encrypted value cannot be read: SECRET_KEY is missing or is not the key it was written with."""


class SecretBox:
    def __init__(self, key: str | None = None) -> None:
        self._fernet = Fernet(key.encode()) if key else None

    @property
    def enabled(self) -> bool:
        return self._fernet is not None

    def encrypt(self, value: str) -> str:
        if self._fernet is None or value.startswith(PREFIX):
            return value
        return PREFIX + self._fernet.encrypt(value.encode()).decode()

    def decrypt(self, stored: str) -> str:
        if not stored.startswith(PREFIX):
            return stored
        if self._fernet is None:
            raise SecretsUnavailable("SECRET_KEY is not set, encrypted values cannot be read")
        try:
            return self._fernet.decrypt(stored[len(PREFIX) :].encode()).decode()
        except InvalidToken as e:
            raise SecretsUnavailable("SECRET_KEY does not match the key the values were encrypted with") from e


_box = SecretBox()


def configure(key: str | None) -> SecretBox:
    global _box
    _box = SecretBox(key or None)
    return _box


def box() -> SecretBox:
    return _box


def valid_key(key: str) -> bool:
    try:
        Fernet(key.encode())
        return True
    except (ValueError, TypeError):
        return False


class EncryptedText(TypeDecorator):
    """A text column that is encrypted on write and decrypted on read."""

    impl = Text
    cache_ok = True

    def process_bind_param(self, value, dialect):
        return None if value is None else _box.encrypt(value)

    def process_result_value(self, value, dialect):
        return None if value is None else _box.decrypt(value)


async def encrypt_existing(session: AsyncSession, columns) -> int:
    """Startup check for every encrypted column: encrypts plaintext rows when a key is set, and
    fails fast when encrypted rows exist that the current key cannot read. Returns rows encrypted."""
    from sqlalchemy import column, table

    changed = 0
    for model, attr in columns:
        name = getattr(model, attr).property.columns[0].name
        raw = table(model.__tablename__, column("id"), column(name))
        rows = (await session.execute(select(raw.c.id, raw.c[name]).where(raw.c[name].is_not(None)))).all()
        for row_id, value in rows:
            if value.startswith(PREFIX):
                _box.decrypt(value)  # raises SecretsUnavailable on a wrong or missing key
            elif _box.enabled:
                await session.execute(update(raw).where(raw.c.id == row_id).values({name: _box.encrypt(value)}))
                changed += 1
    if changed:
        await session.commit()
        log.warning("encrypted %d stored secrets that were still plaintext", changed)
    return changed
