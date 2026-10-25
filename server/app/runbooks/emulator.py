"""A small emulated Windows environment for runbooks: AD accounts, NTFS shares, databases,
VMs and services. Runs change its in-memory state, so a second run sees the first one
(an unlocked account stays unlocked) until the server restarts.

Nothing here reaches a real directory or server.
"""

import asyncio
import secrets
from dataclasses import dataclass, field
from datetime import UTC, datetime

DOMAIN = "FACTORY"
BASE_DN = "DC=factory,DC=local"


@dataclass
class Account:
    sam: str
    name: str
    department: str
    groups: tuple[str, ...]
    enabled: bool = True
    locked: bool = False
    must_change_password: bool = False


@dataclass
class Ace:
    principal: str
    rights: str
    allow: bool = True


@dataclass
class RunResult:
    exit_code: int
    stdout: str
    stderr: str = ""
    # Shown to the operator once and never stored: a temporary password.
    secret: str | None = None


# Strongest first: each level contains the ones after it.
RIGHTS = ("FullControl", "Modify", "ReadAndExecute", "ListDirectory")
RIGHTS_RU = {
    "FullControl": "полный доступ",
    "Modify": "чтение, запись, удаление",
    "ReadAndExecute": "только чтение",
    "ListDirectory": "только просмотр списка",
}

DATABASES = {
    "buh": ("srv-sql", "1C_Buh", 18.4),
    "zup": ("srv-sql", "1C_ZUP", 6.2),
    "mes": ("srv-sql", "MES_Prod", 42.7),
}


def _accounts() -> dict[str, Account]:
    accounts = [
        Account("i.ivanov", "Иванов Иван", "Бухгалтерия", ("Domain Users", "GG_Buh_RW"), locked=True),
        Account("p.petrova", "Петрова Полина", "ОТК", ("Domain Users", "GG_OTK_RW", "GG_Buh_RO")),
        Account("s.sidorov", "Сидоров Сергей", "Цех 1", ("Domain Users", "GG_Ceh_RW"), enabled=False),
        Account("a.kuznetsov", "Кузнецов Алексей", "ИТ", ("Domain Users", "Domain Admins")),
        Account("m.smirnova", "Смирнова Мария", "Склад", ("Domain Users", "GG_Sklad_RW"), locked=True),
    ]
    return {a.sam: a for a in accounts}


def _shares() -> dict[str, list[Ace]]:
    admins = Ace("Domain Admins", "FullControl")
    return {
        r"\\fs01\Бухгалтерия": [admins, Ace("GG_Buh_RW", "Modify"), Ace("GG_Buh_RO", "ReadAndExecute")],
        r"\\fs01\ОТК": [admins, Ace("GG_OTK_RW", "Modify"), Ace("GG_Ceh_RW", "ReadAndExecute")],
        r"\\fs01\Склад": [admins, Ace("GG_Sklad_RW", "Modify"), Ace("Domain Users", "ListDirectory")],
        r"\\fs01\Общая": [admins, Ace("Domain Users", "Modify"), Ace("GG_Ceh_RW", "Modify", allow=False)],
    }


@dataclass
class RunbookEnvironment:
    step_delay: float = 0.0
    accounts: dict[str, Account] = field(default_factory=_accounts)
    shares: dict[str, list[Ace]] = field(default_factory=_shares)
    snapshots: dict[str, list[str]] = field(default_factory=dict)

    async def run(self, runbook_id: str, params: dict[str, str]) -> RunResult:
        handler = getattr(self, f"_{runbook_id}")
        return await handler(**params)

    async def _step(self) -> None:
        if self.step_delay > 0:
            await asyncio.sleep(self.step_delay)

    def _account(self, sam: str) -> Account | None:
        # Accept DOMAIN\user and any case, as admins type it both ways.
        return self.accounts.get(sam.split("\\")[-1].lower())

    def _group(self, name: str) -> str | None:
        groups = {ace.principal.lower(): ace.principal for aces in self.shares.values() for ace in aces}
        groups.update({g.lower(): g for acc in self.accounts.values() for g in acc.groups})
        return groups.get(name.split("\\")[-1].lower())

    @staticmethod
    def _no_account(sam: str) -> RunResult:
        return RunResult(
            exit_code=1,
            stdout="",
            stderr=f"Get-ADUser : Cannot find an object with identity: '{sam}' under: '{BASE_DN}'.",
        )

    # --- Active Directory ---

    async def _ad_reset_password(self, account: str) -> RunResult:
        acc = self._account(account)
        await self._step()
        if acc is None:
            return self._no_account(account)
        password = _temporary_password()
        await self._step()
        acc.must_change_password = True
        out = [
            f"Get-ADUser {acc.sam}: {acc.name}, {acc.department}",
            f"Set-ADAccountPassword -Identity {acc.sam} -Reset: OK",
            f"Set-ADUser -Identity {acc.sam} -ChangePasswordAtLogon $true (pwdLastSet = 0): OK",
            "Временный пароль показан в приложении один раз и в журнал не записан.",
        ]
        if acc.locked:
            out.append("Внимание: учётная запись заблокирована, войти с новым паролем не получится до разблокировки.")
        if not acc.enabled:
            out.append("Внимание: учётная запись отключена.")
        return RunResult(0, "\n".join(out), secret=password)

    async def _ad_unlock_account(self, account: str) -> RunResult:
        acc = self._account(account)
        await self._step()
        if acc is None:
            return self._no_account(account)
        if not acc.locked:
            return RunResult(0, f"Get-ADUser {acc.sam}: {acc.name}\nУчётная запись не заблокирована, изменений нет.")
        await self._step()
        acc.locked = False
        return RunResult(
            0,
            f"Get-ADUser {acc.sam}: {acc.name}\nUnlock-ADAccount -Identity {acc.sam} (lockoutTime = 0): OK",
        )

    async def _ad_set_enabled(self, account: str, action: str) -> RunResult:
        acc = self._account(account)
        await self._step()
        if acc is None:
            return self._no_account(account)
        enable = action == "enable"
        if acc.enabled == enable:
            state = "включена" if enable else "отключена"
            return RunResult(0, f"Get-ADUser {acc.sam}: {acc.name}\nУчётная запись уже {state}, изменений нет.")
        await self._step()
        acc.enabled = enable
        cmd = "Enable-ADAccount" if enable else "Disable-ADAccount"
        return RunResult(0, f"Get-ADUser {acc.sam}: {acc.name}\n{cmd} -Identity {acc.sam}: OK")

    # --- file servers ---

    async def _fs_effective_access(self, principal: str, path: str) -> RunResult:
        await self._step()
        share = next((k for k in self.shares if k.lower() == path.rstrip("\\").lower()), None)
        if share is None:
            known = ", ".join(self.shares)
            return RunResult(1, "", f"Get-Acl : Cannot find path '{path}' because it does not exist. Доступны: {known}")
        acc = self._account(principal)
        if acc is not None:
            subject, groups = f"{DOMAIN}\\{acc.sam}", set(acc.groups)
        elif (group := self._group(principal)) is not None:
            subject, groups = f"{DOMAIN}\\{group}", {group}
        else:
            return self._no_account(principal)
        aces = self.shares[share]
        await self._step()
        lines = [f"Путь: {share}", f"Субъект: {subject}", f"Группы: {', '.join(sorted(groups))}", "ACL:"]
        lines += [f"  {'ALLOW' if a.allow else 'DENY '}  {a.principal:<14} {a.rights}" for a in aces]
        mine = [a for a in aces if a.principal in groups]
        allowed = [RIGHTS.index(a.rights) for a in mine if a.allow]
        denied = [RIGHTS.index(a.rights) for a in mine if not a.allow]
        # A level is held when an allowed level contains it (is as strong or stronger) and no deny
        # covers it; a deny on a level also removes every stronger level, which contains it.
        effective = next(
            (
                level
                for i, level in enumerate(RIGHTS)
                if any(a <= i for a in allowed) and not any(i <= d for d in denied)
            ),
            None,
        )
        if effective is None:
            lines.append("Эффективные права: нет доступа")
        else:
            lines.append(f"Эффективные права: {effective} ({RIGHTS_RU[effective]})")
        return RunResult(0, "\n".join(lines))

    # --- infrastructure ---

    async def _backup_database(self, database: str) -> RunResult:
        server, name, size_gb = DATABASES[database]
        stamp = datetime.now(UTC).strftime("%Y%m%d_%H%M")
        file = rf"\\backup01\sql\{name}_{stamp}.bak"
        out = [f"{server}: BACKUP DATABASE [{name}] TO DISK = N'{file}' WITH COMPRESSION, CHECKSUM"]
        for pct in (10, 40, 70, 100):
            await self._step()
            out.append(f"{pct} percent processed.")
        out.append(
            f"BACKUP DATABASE successfully processed. Размер копии: {size_gb * 0.31:.1f} ГБ (база {size_gb} ГБ)."
        )
        out.append(f"RESTORE VERIFYONLY: The backup set on file 1 is valid. Файл: {file}")
        return RunResult(0, "\n".join(out))

    async def _vm_snapshot(self, vm: str, name: str | None = None) -> RunResult:
        title = name or f"netadmin-{datetime.now(UTC):%Y%m%d-%H%M}"
        await self._step()
        existing = self.snapshots.setdefault(vm, [])
        if title in existing:
            return RunResult(1, "", f"New-Snapshot : A snapshot named '{title}' already exists on VM '{vm}'.")
        await self._step()
        existing.append(title)
        return RunResult(
            0,
            f"Connect-VIServer vcenter01: OK\nNew-Snapshot -VM {vm} -Name '{title}' -Memory:$false -Quiesce:$true: OK\n"
            f"Снапшотов у {vm}: {len(existing)}",
        )

    async def _service_restart(self, service: str) -> RunResult:
        host, name = service.split("/", 1)
        out = [f"Restart-Service -ComputerName {host} -Name '{name}'"]
        await self._step()
        out.append(f"{name}: Stopping … Stopped")
        await self._step()
        out.append(f"{name}: Starting … Running")
        return RunResult(0, "\n".join(out))


def _temporary_password() -> str:
    # Readable over the phone, meets the default AD complexity policy.
    words = ("Zavod", "Ceh", "Sklad", "Pechat", "Smena", "Detal")
    return f"{secrets.choice(words)}-{secrets.randbelow(9000) + 1000}-{secrets.token_hex(2)}"
