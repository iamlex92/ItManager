"""The agent's config file: the same KEY=value settings as the Docker .env, kept in
/etc/netadmin/netadmin.env on Debian and %ProgramData%\\NetAdmin\\netadmin.env on Windows.
The installers create it once with fresh random keys and never overwrite it."""

import os
import secrets
import sys
from base64 import urlsafe_b64encode
from pathlib import Path
from urllib.parse import quote

from dotenv import dotenv_values


def default_path() -> Path:
    if env := os.environ.get("NETADMIN_CONFIG"):
        return Path(env)
    if sys.platform == "win32":
        return Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / "NetAdmin" / "netadmin.env"
    return Path("/etc/netadmin/netadmin.env")


def read(path: Path) -> dict[str, str]:
    return {k: v for k, v in dotenv_values(path).items() if v is not None}


def load_into_env(path: Path) -> dict[str, str]:
    """Settings read the environment, so the file goes there. Variables already set win,
    which lets a one-off run override a value."""
    values = read(path)
    for key, value in values.items():
        os.environ.setdefault(key, value)
    return values


def new_secret_key() -> str:
    return urlsafe_b64encode(secrets.token_bytes(32)).decode()


def render(
    *,
    database_url: str,
    managed_data: Path | None = None,
    pg_port: int | None = None,
    pg_bin: Path | None = None,
    backup_dir: Path | None = None,
    trap_port: int = 0,
    api_token: str | None = None,
    secret_key: str | None = None,
) -> str:
    managed = ""
    if managed_data:
        managed = (
            "\n# Встроенная база: создаётся при первом запуске, доступна только с этого компьютера.\n"
            f"PG_MANAGED_DATA={managed_data}\n"
            f"PG_PORT={pg_port}\n"
        )
    return f"""# Настройки сервера NetAdmin. После правки перезапустите службу NetAdmin.
# Файл секретный: в нём ключ приложения и ключ шифрования.

# Ключ, который вводится в приложении: ⚙ → API-токен.
API_TOKEN={api_token or secrets.token_urlsafe(32)}

# Шифрует SNMP community и пароли в базе. Сохраните копию в менеджере паролей:
# без него восстановленная из копии база не читается.
SECRET_KEY={secret_key or new_secret_key()}

DATABASE_URL={database_url}
{managed}
# Адрес и порт, по которым подключаются телефоны. 0.0.0.0: на всех сетевых интерфейсах.
API_BIND=0.0.0.0
API_PORT=8000

# Резервные копии базы (pg_dump): раз в N часов, хранятся N дней.
BACKUP_DIR={backup_dir or ""}
BACKUP_INTERVAL_HOURS=24
BACKUP_KEEP_DAYS=14
PG_BIN_DIR={pg_bin or ""}

# Безопасный режим по умолчанию: коммутаторы и мониторинг эмулируются, на оборудование ничего не уходит.
# HAL_MODE=real: работа с боевыми коммутаторами (драйвер ещё не готов).
HAL_MODE=emulator
# Важно только при HAL_MODE=real. Оставьте false, пока не решено разрешить серверу менять
# конфигурацию коммутаторов; даже тогда каждое изменение подтверждается в приложении.
ALLOW_DEVICE_WRITES=false
# real: только SNMP GET и ping, без изменений на устройствах.
MONITOR_MODE=emulator
# Сканер сети (кнопка «Сканировать» в приложении и веб-консоли): ping, проверка открытых портов, баннеры
# и SNMP GET к указанным подсетям. Только чтение и только по кнопке. emulator: ответы из учёта, сеть не трогается.
DISCOVERY_MODE=real

# Runbooks (AD, права на папки). emulator: встроенная учебная среда, настоящий AD не затрагивается.
# real: AD по LDAPS и права на папки по WinRM (см. DEPLOY.md, раздел «Runbooks с настоящим AD»).
RUNBOOK_MODE=emulator
# Важно только при RUNBOOK_MODE=real. false: сброс пароля, разблокировка и отключение учётных записей
# только показывают, что нужно сделать. Даже при true каждое изменение подтверждается в приложении.
ALLOW_RUNBOOK_WRITES=false
# RUNBOOK_CREDENTIAL=ad-runbooks
# AD_LDAP_URL=ldaps://dc01.factory.local
# RUNBOOK_FILE_SERVERS=fs01

# SNMP-трапы (linkDown/linkUp) от коммутаторов. 0: выключено.
TRAP_LISTEN_PORT={trap_port}
TRAP_COMMUNITY=public

# Уведомления в Telegram: включаются, когда заданы оба значения (см. DEPLOY.md).
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
# TELEGRAM_PROXY=http://proxy.factory.local:3128

SNMP_VERSION=2c
"""


def managed_database_url(port: int, password: str, user: str = "netadmin", database: str = "netadmin") -> str:
    return f"postgresql+asyncpg://{user}:{quote(password, safe='')}@127.0.0.1:{port}/{database}"


def write_new(path: Path, text: str) -> bool:
    """Create the file with owner-only permissions. False if it already exists (kept as is)."""
    if path.exists():
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return True
