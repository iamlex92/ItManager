package com.itmanager.app.data.model

/**
 * Domain computers: what the admin script Connect-DomainPC.ps1 did, run by the server — Windows over WinRM,
 * Astra Linux over SSH with sudo; the server tells which by the port that answers.
 * [changes]: it changes the computer (needs ALLOW_RUNBOOK_WRITES on the server);
 * [confirm]: asked once more with a fingerprint before it runs; [astraOnly]: shown for Astra Linux only.
 */
enum class PcAction(
    val wire: String,
    val title: String,
    val changes: Boolean = false,
    val confirm: Boolean = false,
    val astraOnly: Boolean = false,
) {
    INFO("info", "Сведения"),
    DISKS("disks", "Свободное место"),
    FOLDERS("folders", "Крупные папки"),
    PROCESSES("processes", "Процессы"),
    GPUPDATE("gpupdate", "Обновить групповые политики", changes = true),
    CLEANUP("cleanup", "Очистка", changes = true, confirm = true),
    REBOOT("reboot", "Перезагрузить", changes = true, confirm = true),
    KILL("kill", "Завершить процесс", changes = true, confirm = true),
    COMMAND("command", "Команда", changes = true, confirm = true),
    /** Windows: the msra command to run on the helper's computer; Astra: x11vnc on the user's screen. */
    ASSIST("assist", "Удалённая помощь"),
    UPDATE("update", "Обновить пакеты", changes = true, confirm = true, astraOnly = true),
    ADMIN_ADD("admin_add", "Выдать sudo", changes = true, confirm = true, astraOnly = true),
    ADMIN_REMOVE("admin_remove", "Снять sudo", changes = true, confirm = true, astraOnly = true),
}

enum class PcPlatform(val title: String) {
    WINDOWS("Windows"),
    ASTRA("Astra Linux");

    companion object {
        fun parse(raw: String?): PcPlatform = if (raw == "astra") ASTRA else WINDOWS
    }
}

/**
 * Remote help on a computer. «msra»: [command] is run on the helper's own Windows computer; «vnc»: x11vnc waits on
 * [address] with a one-time [password] for [expiresMinutes], and the user at [display] accepts the helper.
 */
data class RemoteHelp(
    val method: String,
    val host: String,
    val command: String? = null,
    val needs: List<String> = emptyList(),
    val address: String? = null,
    val password: String? = null,
    val url: String? = null,
    val user: String? = null,
    val display: String? = null,
    val viewOnly: Boolean = false,
    val expiresMinutes: Int? = null,
) {
    val isVnc: Boolean get() = method == "vnc"
}

/** «real»: WinRM with the server's domain login; «emulator»: made-up answers. */
data class PcStatus(val mode: String, val writesEnabled: Boolean, val loginConfigured: Boolean, val adConfigured: Boolean) {
    val emulated: Boolean get() = mode != "real"
}

data class PcSweepHit(val ip: String, val name: String?, val deviceId: String?, val deviceName: String?) {
    /** What to open: the DNS name when there is one, as the script did (WinRM prefers names). */
    val target: String get() = name ?: ip
}

data class OneCLicence(val path: String, val date: String?)

data class PcInfo(
    val computer: String?,
    val os: String?,
    val architecture: String?,
    val build: String?,
    val uptimeMinutes: Long?,
    val cpu: String?,
    val ramGb: Double?,
    val user: String?,
    val windowsBiosKey: String?,
    val windowsRegistryKey: String?,
    val officeInstalled: Boolean,
    /** Last five characters of each installed Office key. */
    val officeKeys: List<String>,
    val oneC: List<OneCLicence>,
    val platform: PcPlatform = PcPlatform.WINDOWS,
    /** Astra Linux: the X display of the logged-in user, who may sudo, what remote help and policies need. */
    val display: String? = null,
    val admins: List<String> = emptyList(),
    val x11vnc: Boolean? = null,
    val sambaGpupdate: Boolean? = null,
) {
    val uptimeText: String?
        get() = uptimeMinutes?.let { "${it / 1440} дн. ${it % 1440 / 60} ч ${it % 60} мин" }

    val officeText: String
        get() = when {
            !officeInstalled -> "Не установлен"
            officeKeys.isEmpty() -> "Ключи не найдены (возможно, Office 365)"
            else -> officeKeys.joinToString(", ") { "*****-$it" }
        }
}

data class PcDisk(val drive: String, val label: String?, val freeGb: Double, val totalGb: Double) {
    val usedPercent: Int get() = if (totalGb > 0) ((1 - freeGb / totalGb) * 100).toInt().coerceIn(0, 100) else 0
}

data class PcFolder(val name: String, val sizeGb: Double)

data class PcProcess(val id: Int, val name: String, val ramMb: Double)

/** What an action brought back, by kind. */
sealed interface PcOutcome {
    data class Info(val info: PcInfo) : PcOutcome
    data class Disks(val disks: List<PcDisk>) : PcOutcome
    data class Folders(val folders: List<PcFolder>) : PcOutcome
    data class Processes(val processes: List<PcProcess>) : PcOutcome
    data class Cleaned(val freedMb: Double, val freeGb: Double) : PcOutcome
    data class Stopped(val name: String, val count: Int) : PcOutcome
    data class Text(val text: String) : PcOutcome
    data class Assist(val help: RemoteHelp) : PcOutcome
}

data class PcResult(
    val host: String,
    val action: PcAction,
    val ok: Boolean,
    val error: String,
    val simulated: Boolean,
    val outcome: PcOutcome?,
    val platform: PcPlatform = PcPlatform.WINDOWS,
)

data class PcKeyRow(
    val host: String,
    val ok: Boolean,
    val error: String,
    val computer: String?,
    val user: String?,
    val os: String?,
    val windowsBiosKey: String?,
    val windowsRegistryKey: String?,
    val office: String,
    val oneC: String,
)

data class PcKeyJob(
    val id: String,
    val source: String,
    /** running, done or failed. */
    val status: String,
    val stage: String,
    val total: Int,
    val online: Int,
    val done: Int,
    val error: String,
    val simulated: Boolean,
    val rows: List<PcKeyRow>,
) {
    val running: Boolean get() = status == "running"
}
