"""Server settings an administrator changes in the web console or the app instead of editing netadmin.env.

netadmin.env (or the environment) stays the base; a value saved here overrides it from the next start of the
service, because drivers, pollers and listeners are built once at start. What cannot be changed here: the database
address, API_TOKEN, SECRET_KEY and the listening address — without them the server cannot start or be reached, so
they stay in the file. Secrets (the enable password, SNMPv3 keys, the Telegram token) are stored encrypted and are
never sent back, only whether they are set.
"""

import json
import re
from dataclasses import dataclass, field
from typing import Annotated, Any

from pydantic import TypeAdapter, ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import Settings
from .models import ServerSetting


@dataclass(frozen=True)
class Choice:
    value: str
    label: str


@dataclass(frozen=True)
class Spec:
    key: str
    label: str
    help: str = ""
    # bool, int, float, text, pem (several lines), choice, secret, credential
    kind: str = "text"
    choices: tuple[Choice, ...] = ()
    # For kind=credential: which stored logins fit (ssh, winrm).
    credential_kind: str = ""


@dataclass(frozen=True)
class Group:
    title: str
    note: str
    fields: tuple[Spec, ...] = field(default_factory=tuple)


EMU_REAL = (Choice("emulator", "Эмуляция"), Choice("real", "Настоящие"))

GROUPS: tuple[Group, ...] = (
    Group(
        "Коммутаторы",
        "Управление Cisco IOS и Eltex MES по SSH. Изменения на коммутаторах отдельно разрешаются ниже.",
        (
            Spec("hal_mode", "Работа с коммутаторами", "Эмуляция: на коммутаторы ничего не уходит. "
                 "Настоящие: состояние портов, конфигурация, ping по SSH.", "choice",
                 (Choice("emulator", "Эмуляция"), Choice("real", "Настоящие (SSH)"))),
            Spec("allow_device_writes", "Разрешить изменения на коммутаторах",
                 "VLAN и режим портов, выключение, перезапуск порта, сохранение конфигурации. "
                 "Каждое изменение всё равно подтверждается.", "bool"),
            Spec("switch_credential", "Общий логин SSH",
                 "Для коммутаторов, у которых в карточке логин не выбран.", "credential", credential_kind="ssh"),
            Spec("switch_enable_secret", "Пароль enable", "Если отличается от пароля входа.", "secret"),
            Spec("switch_ssh_port", "Порт SSH", kind="int"),
            Spec("poll_interval_seconds", "Опрос портов, с",
                 "Настоящие коммутаторы по SSH — не чаще раза в минуту, по SNMP — раза в 15 с. 0 — выключен.",
                 "float"),
        ),
    ),
    Group(
        "Мониторинг и SNMP",
        "Только чтение: ping, SNMP GET. На устройствах ничего не меняется.",
        (
            Spec("monitor_mode", "Опрос устройств", "Настоящие: ping, линк портов, тонер и счётчики МФУ, "
                 "загрузка портов. Эмуляция: цифры придумываются.", "choice", EMU_REAL),
            Spec("discovery_mode", "Сканер сети", "Настоящие: ping, порты и SNMP к подсетям, только по кнопке.",
                 "choice", EMU_REAL),
            Spec("heartbeat_interval_seconds", "Ping устройств, с", "0 — выключен.", "float"),
            Spec("printer_poll_interval_seconds", "Опрос МФУ, с", "0 — выключен.", "float"),
            Spec("traffic_interval_seconds", "Загрузка портов, с", "0 — выключена.", "float"),
            Spec("traffic_keep_days", "Хранить загрузку портов, дней", kind="int"),
            Spec("snmp_version", "Версия SNMP", "2c: community из карточки устройства. 3: пользователь ниже.",
                 "choice", (Choice("2c", "v2c"), Choice("3", "v3"))),
            Spec("snmp_timeout_seconds", "Тайм-аут SNMP, с", kind="float"),
            Spec("snmp_v3_user", "SNMPv3: пользователь"),
            Spec("snmp_v3_auth_protocol", "SNMPv3: аутентификация", kind="choice",
                 choices=(Choice("MD5", "MD5"), Choice("SHA", "SHA"), Choice("SHA256", "SHA-256"))),
            Spec("snmp_v3_auth_key", "SNMPv3: ключ аутентификации", kind="secret"),
            Spec("snmp_v3_priv_protocol", "SNMPv3: шифрование", kind="choice",
                 choices=(Choice("AES", "AES"), Choice("DES", "DES"))),
            Spec("snmp_v3_priv_key", "SNMPv3: ключ шифрования", kind="secret"),
            Spec("trap_listen_port", "Порт приёма трапов",
                 "linkDown/linkUp от коммутаторов. 0 — выключен; 162 — стандартный.", "int"),
            Spec("trap_community", "Community трапов"),
        ),
    ),
    Group(
        "Компьютеры домена и сценарии",
        "WinRM к компьютерам и AD по LDAPS от учётной записи из «Учётных данных».",
        (
            Spec("pc_mode", "Компьютеры домена", "Сведения, ключи, обслуживание ПК по WinRM.", "choice",
                 (Choice("", "Как сценарии"), Choice("emulator", "Эмуляция"), Choice("real", "Настоящие (WinRM)"))),
            Spec("runbook_mode", "Сценарии", "Сброс пароля AD, права на папки.", "choice", EMU_REAL),
            Spec("allow_runbook_writes", "Разрешить изменения",
                 "Очистка, перезагрузка, процессы и консоль на ПК; сброс паролей и блокировки в AD. "
                 "Каждое изменение подтверждается.", "bool"),
            Spec("runbook_credential", "Учётная запись домена", "Администратор ПК и AD (вид WinRM).",
                 "credential", credential_kind="winrm"),
            Spec("ad_ldap_url", "Контроллер домена", "ldaps://dc01.ecotehs.local (Samba или Windows): AD, групповые "
                 "политики, «все ПК из AD», сценарии."),
            Spec("ad_start_tls", "LDAP со StartTLS", "Для адреса ldap://.", "bool"),
            Spec("ad_ca_pem", "Сертификат контроллера (ЦС)",
                 "Если сертификат LDAPS выдан своим ЦС или самоподписан. Samba: содержимое "
                 "/var/lib/samba/private/tls/ca.pem на контроллере. Windows: сертификат корпоративного ЦС.", "pem"),
            Spec("ad_base_dn", "Корень поиска в AD", "Пусто — берётся с контроллера."),
            Spec("runbook_file_servers", "Файловые серверы", "Через запятую: fs01,fs02."),
            Spec("gpo_host", "Сервер GPMC (необязательно)",
                 "Пусто — политики правятся напрямую через LDAP и SYSVOL контроллера (Samba и Windows). "
                 "Windows-сервер с GPMC и WinRM — если нужны PowerShell GroupPolicy и резервные копии."),
            Spec("gpo_backup_path", "Резервные копии GPO", "Только с сервером GPMC: папка на нём, например "
                 "C:\\GPOBackup — копия политики перед каждым изменением."),
            Spec("winrm_https", "WinRM по HTTPS", "Порт 5986, на ПК нужен сертификат.", "bool"),
            Spec("winrm_port", "Порт WinRM", "0 — 5985 или 5986 для HTTPS.", "int"),
            Spec("linux_credential", "Учётная запись для Astra Linux",
                 "SSH и sudo на ПК с Astra Linux. Пусто — «Учётная запись домена» (с winbind она входит по SSH, "
                 "если ей разрешён sudo).", "credential", credential_kind="ssh"),
            Spec("linux_ssh_port", "Порт SSH на ПК с Astra Linux", kind="int"),
        ),
    ),
    Group(
        "Уведомления в Telegram",
        "Нужны и токен бота, и чат; без них уведомления выключены.",
        (
            Spec("telegram_bot_token", "Токен бота", kind="secret"),
            Spec("telegram_chat_id", "Чат"),
            Spec("telegram_min_severity", "С какой важности", kind="choice",
                 choices=(Choice("INFO", "Все"), Choice("WARNING", "Предупреждения"), Choice("CRITICAL", "Критичные"))),
            Spec("telegram_delay_seconds", "Задержка, с",
                 "Событие уходит, только если ещё не закрылось: мигающий порт не будит.", "float"),
            Spec("telegram_notify_resolved", "Сообщать о закрытии", kind="bool"),
            Spec("telegram_proxy", "Прокси", "http://proxy.local:3128, если у сервера нет прямого выхода."),
        ),
    ),
    Group(
        "Резервные копии базы",
        "Папка задаётся при установке (BACKUP_DIR).",
        (
            Spec("backup_interval_hours", "Каждые, ч", kind="float"),
            Spec("backup_keep_days", "Хранить, дней", kind="int"),
        ),
    ),
)

SPECS: dict[str, Spec] = {s.key: s for g in GROUPS for s in g.fields}


class SettingError(ValueError):
    def __init__(self, key: str, message: str) -> None:
        super().__init__(f"{SPECS[key].label if key in SPECS else key}: {message}")
        self.key = key


def _adapter(key: str) -> TypeAdapter:
    info = Settings.model_fields[key]
    # The field's own limits (ge, le, pattern) come with its metadata.
    return TypeAdapter(Annotated[info.annotation, *info.metadata] if info.metadata else info.annotation)


def validate(key: str, value: Any) -> Any:
    """The value as the setting's own type; SettingError when it does not fit."""
    if key not in SPECS:
        raise SettingError(key, "эту настройку нельзя менять здесь")
    if isinstance(value, str):
        value = value.strip()
    if key == "ad_ldap_url" and value and not re.match(r"^ldaps?://[A-Za-z0-9.-]+(:\d+)?/?$", str(value)):
        raise SettingError(key, "нужен адрес вида ldaps://dc01.ecotehs.local")
    if key == "ad_ca_pem" and value:
        from .runbooks.real import normalize_pem

        try:
            value = normalize_pem(str(value))  # stored the way OpenSSL reads it
        except ValueError as e:
            raise SettingError(key, str(e)) from e
    try:
        return _adapter(key).validate_python(value)
    except ValidationError as e:
        raise SettingError(key, e.errors()[0].get("msg", "неверное значение")) from e


def plain(value: Any) -> Any:
    """JSON-friendly form of a setting value (enums become their string)."""
    return getattr(value, "value", value)


def current(settings: Settings) -> dict[str, Any]:
    return {k: plain(getattr(settings, k)) for k in SPECS}


async def load(session: AsyncSession) -> dict[str, Any]:
    """Saved overrides, already of their settings' types; ones that no longer fit are skipped."""
    out: dict[str, Any] = {}
    for row in (await session.scalars(select(ServerSetting))).all():
        try:
            out[row.id] = validate(row.id, json.loads(row.value))
        except (SettingError, ValueError):
            continue
    return out


def apply(settings: Settings, overrides: dict[str, Any]) -> None:
    """Writes the overrides into the running settings; called once at start, before anything is built."""
    for key, value in overrides.items():
        setattr(settings, key, value)


async def save(session: AsyncSession, values: dict[str, Any], reset: list[str]) -> list[str]:
    """Stores validated values and drops reset ones; returns the keys that changed. Does not commit."""
    checked = {k: validate(k, v) for k, v in values.items()}
    for k in reset:
        if k not in SPECS:
            raise SettingError(k, "эту настройку нельзя менять здесь")
    existing = {r.id: r for r in (await session.scalars(select(ServerSetting))).all()}
    changed: list[str] = []
    for key, value in checked.items():
        text = json.dumps(plain(value))
        row = existing.get(key)
        if row is None:
            session.add(ServerSetting(id=key, value=text))
            changed.append(key)
        elif row.value != text:
            row.value = text
            changed.append(key)
    for key in reset:
        if key in existing and key not in checked:
            await session.delete(existing[key])
            changed.append(key)
    return changed
