"""The runbook catalog: what can be run, which arguments it takes, and which runs are critical.

Critical runbooks change something (a password, an account, a server). The app asks for
biometrics before it sends one, and the server refuses them without "confirm": true.
"""

from dataclasses import dataclass, field
from enum import StrEnum


class Category(StrEnum):
    AD = "Active Directory"
    FILES = "Файловые серверы"
    INFRA = "Инфраструктура"


class ParamKind(StrEnum):
    TEXT = "text"
    CHOICE = "choice"


@dataclass(frozen=True)
class Choice:
    value: str
    label: str


@dataclass(frozen=True)
class Param:
    name: str
    label: str
    kind: ParamKind = ParamKind.TEXT
    required: bool = True
    choices: tuple[Choice, ...] = ()
    placeholder: str | None = None


@dataclass(frozen=True)
class Runbook:
    id: str
    category: Category
    title: str
    description: str
    critical: bool
    params: tuple[Param, ...] = field(default_factory=tuple)


ACCOUNT = Param("account", "Учётная запись", placeholder="i.ivanov")

CATALOG: tuple[Runbook, ...] = (
    Runbook(
        "ad_reset_password",
        Category.AD,
        "Сброс пароля",
        "Временный пароль со сменой при входе (pwdLastSet = 0)",
        critical=True,
        params=(ACCOUNT,),
    ),
    Runbook(
        "ad_unlock_account",
        Category.AD,
        "Разблокировка учётной записи",
        "Сброс блокировки после неверных паролей (lockoutTime = 0)",
        critical=False,
        params=(ACCOUNT,),
    ),
    Runbook(
        "ad_set_enabled",
        Category.AD,
        "Включение и отключение учётной записи",
        "Для увольнений и длительного отсутствия",
        critical=True,
        params=(
            ACCOUNT,
            Param(
                "action",
                "Действие",
                ParamKind.CHOICE,
                choices=(Choice("disable", "Отключить"), Choice("enable", "Включить")),
            ),
        ),
    ),
    Runbook(
        "fs_effective_access",
        Category.FILES,
        "Проверка прав на папку",
        "Эффективные права пользователя или группы (NTFS), только чтение",
        critical=False,
        params=(
            Param("principal", "Пользователь или группа", placeholder="i.ivanov"),
            Param("path", "Папка", placeholder=r"\\fs01\Бухгалтерия"),
        ),
    ),
    Runbook(
        "backup_database",
        Category.INFRA,
        "Резервная копия базы данных",
        "Полная копия базы на сервер резервного копирования",
        critical=True,
        params=(
            Param(
                "database",
                "База данных",
                ParamKind.CHOICE,
                choices=(
                    Choice("buh", "1С: Бухгалтерия"),
                    Choice("zup", "1С: Зарплата и кадры"),
                    Choice("mes", "MES: производство"),
                ),
            ),
        ),
    ),
    Runbook(
        "vm_snapshot",
        Category.INFRA,
        "Снапшот виртуальной машины",
        "Перед обновлением или рискованным изменением",
        critical=True,
        params=(
            Param(
                "vm",
                "Виртуальная машина",
                ParamKind.CHOICE,
                choices=(
                    Choice("vm-1c-app", "vm-1c-app (сервер 1С)"),
                    Choice("vm-mes", "vm-mes (MES)"),
                    Choice("vm-dc01", "vm-dc01 (контроллер домена)"),
                ),
            ),
            Param("name", "Имя снапшота", required=False, placeholder="перед обновлением"),
        ),
    ),
    Runbook(
        "service_restart",
        Category.INFRA,
        "Перезапуск службы",
        "Остановка и запуск системной службы на сервере",
        critical=True,
        params=(
            Param(
                "service",
                "Служба",
                ParamKind.CHOICE,
                choices=(
                    Choice("srv-print/Spooler", "Диспетчер печати на srv-print"),
                    Choice("srv-1c/1C:Enterprise 8.3 Server Agent", "Агент сервера 1С на srv-1c"),
                    Choice("srv-mes/MesApi", "MES API на srv-mes"),
                ),
            ),
        ),
    ),
)

BY_ID = {rb.id: rb for rb in CATALOG}


class InvalidParams(ValueError):
    pass


MAX_TEXT = 200


def validate(runbook: Runbook, raw: dict[str, str]) -> dict[str, str]:
    """Trimmed arguments, or InvalidParams naming the first problem in Russian for the app."""
    known = {p.name for p in runbook.params}
    unknown = sorted(set(raw) - known)
    if unknown:
        raise InvalidParams(f"неизвестные параметры: {', '.join(unknown)}")
    clean: dict[str, str] = {}
    for p in runbook.params:
        value = (raw.get(p.name) or "").strip()
        if not value:
            if p.required:
                raise InvalidParams(f"не заполнено поле «{p.label}»")
            continue
        if len(value) > MAX_TEXT:
            raise InvalidParams(f"поле «{p.label}» длиннее {MAX_TEXT} символов")
        if p.kind is ParamKind.CHOICE and value not in {c.value for c in p.choices}:
            raise InvalidParams(f"недопустимое значение поля «{p.label}»")
        clean[p.name] = value
    return clean
