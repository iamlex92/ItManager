package com.itmanager.app.data.model

/**
 * Domain computers: what the admin script Connect-DomainPC.ps1 did, run by the server over WinRM.
 * [changes]: it changes the computer (needs ALLOW_RUNBOOK_WRITES on the server);
 * [confirm]: asked once more with a fingerprint before it runs.
 */
enum class PcAction(val wire: String, val title: String, val changes: Boolean = false, val confirm: Boolean = false) {
    INFO("info", "Сведения и лицензии"),
    DISKS("disks", "Свободное место"),
    FOLDERS("folders", "Крупные папки на C:"),
    PROCESSES("processes", "Процессы"),
    GPUPDATE("gpupdate", "Обновить групповые политики", changes = true),
    CLEANUP("cleanup", "Очистить Temp и кэш обновлений", changes = true, confirm = true),
    REBOOT("reboot", "Перезагрузить", changes = true, confirm = true),
    KILL("kill", "Завершить процесс", changes = true, confirm = true),
    COMMAND("command", "Команда PowerShell", changes = true, confirm = true),
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
}

data class PcResult(
    val host: String,
    val action: PcAction,
    val ok: Boolean,
    val error: String,
    val simulated: Boolean,
    val outcome: PcOutcome?,
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
