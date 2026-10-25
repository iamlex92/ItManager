package com.itmanager.app.data.source

import android.os.Build
import com.itmanager.app.data.model.AuditEntry
import com.itmanager.app.data.model.AuditKind
import com.itmanager.app.data.model.AuthMethod
import com.itmanager.app.data.model.Runbook
import com.itmanager.app.data.model.RunbookChoice
import com.itmanager.app.data.model.RunbookParam
import com.itmanager.app.data.model.RunbookParamKind
import com.itmanager.app.data.model.RunbookRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Runbooks and the audit log without a server: the same catalog and the same emulated
 * AD accounts, shares, databases, VMs and services as the server's emulator.
 * State lives for the app process, so an unlocked account stays unlocked until restart.
 */
internal object DemoRunbooks {
    private val account = RunbookParam("account", "Учётная запись", placeholder = "i.ivanov")

    val catalog = listOf(
        Runbook(
            "ad_reset_password", "Active Directory", "Сброс пароля",
            "Временный пароль со сменой при входе (pwdLastSet = 0)", critical = true, params = listOf(account),
        ),
        Runbook(
            "ad_unlock_account", "Active Directory", "Разблокировка учётной записи",
            "Сброс блокировки после неверных паролей (lockoutTime = 0)", critical = false, params = listOf(account),
        ),
        Runbook(
            "ad_set_enabled", "Active Directory", "Включение и отключение учётной записи",
            "Для увольнений и длительного отсутствия", critical = true,
            params = listOf(
                account,
                choice("action", "Действие", "disable" to "Отключить", "enable" to "Включить"),
            ),
        ),
        Runbook(
            "fs_effective_access", "Файловые серверы", "Проверка прав на папку",
            "Эффективные права пользователя или группы (NTFS), только чтение", critical = false,
            params = listOf(
                RunbookParam("principal", "Пользователь или группа", placeholder = "i.ivanov"),
                RunbookParam("path", "Папка", placeholder = """\\fs01\Бухгалтерия"""),
            ),
        ),
        Runbook(
            "backup_database", "Инфраструктура", "Резервная копия базы данных",
            "Полная копия базы на сервер резервного копирования", critical = true,
            params = listOf(
                choice("database", "База данных", "buh" to "1С: Бухгалтерия", "zup" to "1С: Зарплата и кадры", "mes" to "MES: производство"),
            ),
        ),
        Runbook(
            "vm_snapshot", "Инфраструктура", "Снапшот виртуальной машины",
            "Перед обновлением или рискованным изменением", critical = true,
            params = listOf(
                choice(
                    "vm", "Виртуальная машина",
                    "vm-1c-app" to "vm-1c-app (сервер 1С)", "vm-mes" to "vm-mes (MES)", "vm-dc01" to "vm-dc01 (контроллер домена)",
                ),
                RunbookParam("name", "Имя снапшота", required = false, placeholder = "перед обновлением"),
            ),
        ),
        Runbook(
            "service_restart", "Инфраструктура", "Перезапуск службы",
            "Остановка и запуск системной службы на сервере", critical = true,
            params = listOf(
                choice(
                    "service", "Служба",
                    "srv-print/Spooler" to "Диспетчер печати на srv-print",
                    "srv-1c/1C:Enterprise 8.3 Server Agent" to "Агент сервера 1С на srv-1c",
                    "srv-mes/MesApi" to "MES API на srv-mes",
                ),
            ),
        ),
    )

    private fun choice(name: String, label: String, vararg options: Pair<String, String>) =
        RunbookParam(name, label, RunbookParamKind.CHOICE, choices = options.map { RunbookChoice(it.first, it.second) })

    private class Account(
        val sam: String,
        val name: String,
        val department: String,
        val groups: Set<String>,
        var enabled: Boolean = true,
        var locked: Boolean = false,
    )

    private class Ace(val principal: String, val rights: String, val allow: Boolean = true)

    private val accounts = listOf(
        Account("i.ivanov", "Иванов Иван", "Бухгалтерия", setOf("Domain Users", "GG_Buh_RW"), locked = true),
        Account("p.petrova", "Петрова Полина", "ОТК", setOf("Domain Users", "GG_OTK_RW", "GG_Buh_RO")),
        Account("s.sidorov", "Сидоров Сергей", "Цех 1", setOf("Domain Users", "GG_Ceh_RW"), enabled = false),
        Account("a.kuznetsov", "Кузнецов Алексей", "ИТ", setOf("Domain Users", "Domain Admins")),
        Account("m.smirnova", "Смирнова Мария", "Склад", setOf("Domain Users", "GG_Sklad_RW"), locked = true),
    ).associateBy { it.sam }

    private val admins = Ace("Domain Admins", "FullControl")
    private val shares = mapOf(
        """\\fs01\Бухгалтерия""" to listOf(admins, Ace("GG_Buh_RW", "Modify"), Ace("GG_Buh_RO", "ReadAndExecute")),
        """\\fs01\ОТК""" to listOf(admins, Ace("GG_OTK_RW", "Modify"), Ace("GG_Ceh_RW", "ReadAndExecute")),
        """\\fs01\Склад""" to listOf(admins, Ace("GG_Sklad_RW", "Modify"), Ace("Domain Users", "ListDirectory")),
        """\\fs01\Общая""" to listOf(admins, Ace("Domain Users", "Modify"), Ace("GG_Ceh_RW", "Modify", allow = false)),
    )

    /** Strongest first: each level contains the ones after it. */
    private val rights = listOf("FullControl", "Modify", "ReadAndExecute", "ListDirectory")
    private val rightsRu = mapOf(
        "FullControl" to "полный доступ",
        "Modify" to "чтение, запись, удаление",
        "ReadAndExecute" to "только чтение",
        "ListDirectory" to "только просмотр списка",
    )

    private val databases = mapOf("buh" to ("1C_Buh" to 18.4), "zup" to ("1C_ZUP" to 6.2), "mes" to ("MES_Prod" to 42.7))
    private val snapshots = HashMap<String, MutableList<String>>()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC)
    private val random = SecureRandom()

    private val lock = Mutex()
    private val audit = ArrayDeque<AuditEntry>()

    init {
        val now = Instant.now()
        audit += AuditEntry(
            id = "demo-audit-2", action = "runbook:ad_unlock_account", kind = AuditKind.RUNBOOK, title = "Разблокировка учётной записи",
            triggeredBy = "Pixel 10a · 10.10.20.31", arguments = listOf("account" to "p.petrova"),
            authMethod = AuthMethod.NOT_REQUIRED, executedAt = now.minus(Duration.ofHours(3)), exitCode = 0,
            stdout = "Get-ADUser p.petrova: Петрова Полина\nUnlock-ADAccount -Identity p.petrova (lockoutTime = 0): OK",
            stderr = "", durationMs = 812,
        )
        audit += AuditEntry(
            id = "demo-audit-1", action = "switch_ping", kind = AuditKind.SWITCH, title = "Ping с коммутатора",
            triggeredBy = "api:10.10.20.31", arguments = listOf("device" to "SW-CORE-01", "target" to "10.10.10.22", "count" to "5"),
            authMethod = null, executedAt = now.minus(Duration.ofDays(1)), exitCode = 0,
            stdout = "ping 10.10.10.22 repeat 5\n!!!!!\nSuccess rate is 100 percent (5/5)", stderr = "", durationMs = 1_204,
        )
    }

    private class Result(val exitCode: Int, val stdout: String, val stderr: String = "", val secret: String? = null)

    suspend fun run(runbook: Runbook, arguments: Map<String, String>, authMethod: AuthMethod): RunbookRun {
        val started = Instant.now()
        val result = lock.withLock { execute(runbook.id, arguments) }
        val duration = Duration.between(started, Instant.now()).toMillis()
        val id = UUID.randomUUID().toString()
        record(
            AuditEntry(
                id = id, action = "runbook:${runbook.id}", kind = AuditKind.RUNBOOK, title = runbook.title,
                triggeredBy = "${Build.MODEL} · демо", arguments = arguments.toList(), authMethod = authMethod,
                executedAt = started, exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr,
                durationMs = duration,
            ),
        )
        return RunbookRun(id, runbook.id, result.exitCode, result.stdout, result.stderr, result.secret, started, duration, simulated = true)
    }

    /** Demo switch writes and diagnostics land in the same log, like on the server. */
    suspend fun recordSwitch(title: String, arguments: List<Pair<String, String>>, output: List<String>, exitCode: Int = 0) {
        record(
            AuditEntry(
                id = UUID.randomUUID().toString(), action = title, kind = AuditKind.SWITCH, title = title,
                triggeredBy = "${Build.MODEL} · демо", arguments = arguments, authMethod = null,
                executedAt = Instant.now(), exitCode = exitCode, stdout = output.joinToString("\n"), stderr = "",
                durationMs = null,
            ),
        )
    }

    /** Demo network scans and imports, under the same action names the server uses. */
    suspend fun recordNetwork(
        action: String, title: String, arguments: List<Pair<String, String>>, stdout: String, stderr: String, durationMs: Long?,
    ) {
        record(
            AuditEntry(
                id = UUID.randomUUID().toString(), action = action, kind = AuditKind.NETWORK, title = title,
                triggeredBy = "${Build.MODEL} · демо", arguments = arguments, authMethod = null,
                executedAt = Instant.now(), exitCode = 0, stdout = stdout, stderr = stderr, durationMs = durationMs,
            ),
        )
    }

    /** Demo credential changes; like the server, the arguments never include the secret. */
    suspend fun recordSecurity(action: String, title: String, arguments: List<Pair<String, String>>, summary: String) {
        record(
            AuditEntry(
                id = UUID.randomUUID().toString(), action = action, kind = AuditKind.SECURITY, title = title,
                triggeredBy = "${Build.MODEL} · демо", arguments = arguments, authMethod = null,
                executedAt = Instant.now(), exitCode = 0, stdout = summary, stderr = "", durationMs = null,
            ),
        )
    }

    suspend fun audit(kind: AuditKind?): List<AuditEntry> = lock.withLock {
        audit.filter { kind == null || it.kind == kind }
    }

    private suspend fun record(entry: AuditEntry) = lock.withLock {
        audit.addFirst(entry)
        while (audit.size > 200) audit.removeLast()
    }

    private suspend fun step() = delay(400)

    private fun account(sam: String) = accounts[sam.substringAfterLast('\\').lowercase()]

    private fun noAccount(sam: String) =
        Result(1, "", "Get-ADUser : Cannot find an object with identity: '$sam' under: 'DC=factory,DC=local'.")

    private suspend fun execute(id: String, a: Map<String, String>): Result = when (id) {
        "ad_reset_password" -> resetPassword(a.getValue("account"))
        "ad_unlock_account" -> unlock(a.getValue("account"))
        "ad_set_enabled" -> setEnabled(a.getValue("account"), a.getValue("action") == "enable")
        "fs_effective_access" -> effectiveAccess(a.getValue("principal"), a.getValue("path"))
        "backup_database" -> backup(a.getValue("database"))
        "vm_snapshot" -> snapshot(a.getValue("vm"), a["name"])
        "service_restart" -> restart(a.getValue("service"))
        else -> Result(1, "", "Неизвестный сценарий: $id")
    }

    private suspend fun resetPassword(sam: String): Result {
        step()
        val acc = account(sam) ?: return noAccount(sam)
        step()
        val words = listOf("Zavod", "Ceh", "Sklad", "Pechat", "Smena", "Detal")
        val password = "${words[random.nextInt(words.size)]}-${1000 + random.nextInt(9000)}-${"%04x".format(random.nextInt(0x10000))}"
        val out = mutableListOf(
            "Get-ADUser ${acc.sam}: ${acc.name}, ${acc.department}",
            "Set-ADAccountPassword -Identity ${acc.sam} -Reset: OK",
            "Set-ADUser -Identity ${acc.sam} -ChangePasswordAtLogon \$true (pwdLastSet = 0): OK",
            "Временный пароль показан в приложении один раз и в журнал не записан.",
        )
        if (acc.locked) out += "Внимание: учётная запись заблокирована, войти с новым паролем не получится до разблокировки."
        if (!acc.enabled) out += "Внимание: учётная запись отключена."
        return Result(0, out.joinToString("\n"), secret = password)
    }

    private suspend fun unlock(sam: String): Result {
        step()
        val acc = account(sam) ?: return noAccount(sam)
        if (!acc.locked) return Result(0, "Get-ADUser ${acc.sam}: ${acc.name}\nУчётная запись не заблокирована, изменений нет.")
        step()
        acc.locked = false
        return Result(0, "Get-ADUser ${acc.sam}: ${acc.name}\nUnlock-ADAccount -Identity ${acc.sam} (lockoutTime = 0): OK")
    }

    private suspend fun setEnabled(sam: String, enable: Boolean): Result {
        step()
        val acc = account(sam) ?: return noAccount(sam)
        if (acc.enabled == enable) {
            val state = if (enable) "включена" else "отключена"
            return Result(0, "Get-ADUser ${acc.sam}: ${acc.name}\nУчётная запись уже $state, изменений нет.")
        }
        step()
        acc.enabled = enable
        val cmd = if (enable) "Enable-ADAccount" else "Disable-ADAccount"
        return Result(0, "Get-ADUser ${acc.sam}: ${acc.name}\n$cmd -Identity ${acc.sam}: OK")
    }

    private suspend fun effectiveAccess(principal: String, path: String): Result {
        step()
        val share = shares.keys.firstOrNull { it.equals(path.trimEnd('\\'), ignoreCase = true) }
            ?: return Result(1, "", "Get-Acl : Cannot find path '$path' because it does not exist. Доступны: ${shares.keys.joinToString()}")
        val acc = account(principal)
        val knownGroups = (shares.values.flatten().map { it.principal } + accounts.values.flatMap { it.groups })
            .associateBy { it.lowercase() }
        val (subject, groups) = when {
            acc != null -> "FACTORY\\${acc.sam}" to acc.groups
            else -> knownGroups[principal.substringAfterLast('\\').lowercase()]?.let { "FACTORY\\$it" to setOf(it) }
                ?: return noAccount(principal)
        }
        step()
        val aces = shares.getValue(share)
        val lines = mutableListOf("Путь: $share", "Субъект: $subject", "Группы: ${groups.sorted().joinToString()}", "ACL:")
        aces.forEach { lines += "  ${if (it.allow) "ALLOW" else "DENY "}  ${it.principal.padEnd(14)} ${it.rights}" }
        val mine = aces.filter { it.principal in groups }
        val allowed = mine.filter { it.allow }.map { rights.indexOf(it.rights) }
        val denied = mine.filterNot { it.allow }.map { rights.indexOf(it.rights) }
        // Held when an allowed level contains it and no deny covers it (a deny also removes stronger levels).
        val effective = rights.indices.firstOrNull { i -> allowed.any { it <= i } && denied.none { i <= it } }?.let { rights[it] }
        lines += if (effective == null) "Эффективные права: нет доступа" else "Эффективные права: $effective (${rightsRu[effective]})"
        return Result(0, lines.joinToString("\n"))
    }

    private suspend fun backup(database: String): Result {
        val (name, sizeGb) = databases.getValue(database)
        val file = """\\backup01\sql\${name}_${stamp.format(Instant.now()).replace('-', '_')}.bak"""
        val out = mutableListOf("srv-sql: BACKUP DATABASE [$name] TO DISK = N'$file' WITH COMPRESSION, CHECKSUM")
        listOf(10, 40, 70, 100).forEach {
            step()
            out += "$it percent processed."
        }
        out += "BACKUP DATABASE successfully processed. Размер копии: ${"%.1f".format(sizeGb * 0.31)} ГБ (база $sizeGb ГБ)."
        out += "RESTORE VERIFYONLY: The backup set on file 1 is valid. Файл: $file"
        return Result(0, out.joinToString("\n"))
    }

    private suspend fun snapshot(vm: String, name: String?): Result {
        val title = name ?: "netadmin-${stamp.format(Instant.now())}"
        step()
        val existing = snapshots.getOrPut(vm) { mutableListOf() }
        if (title in existing) return Result(1, "", "New-Snapshot : A snapshot named '$title' already exists on VM '$vm'.")
        step()
        existing += title
        return Result(
            0,
            "Connect-VIServer vcenter01: OK\nNew-Snapshot -VM $vm -Name '$title' -Memory:\$false -Quiesce:\$true: OK\nСнапшотов у $vm: ${existing.size}",
        )
    }

    private suspend fun restart(service: String): Result {
        val host = service.substringBefore('/')
        val name = service.substringAfter('/')
        step()
        val stopped = "$name: Stopping … Stopped"
        step()
        return Result(0, "Restart-Service -ComputerName $host -Name '$name'\n$stopped\n$name: Starting … Running")
    }
}
