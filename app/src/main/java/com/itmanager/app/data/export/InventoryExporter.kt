package com.itmanager.app.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.itmanager.app.core.settings.SettingsRepository
import com.itmanager.app.data.model.DeviceType
import com.itmanager.app.data.source.DcimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
}

data class ExportedFile(val uri: Uri, val mime: String, val name: String)

/**
 * Builds the inventory report from the current data source (demo or server) and writes it to the app's
 * cache, from where the share sheet hands it to mail, a messenger or Files. Reads only.
 */
@Singleton
class InventoryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DcimRepository,
    private val settings: SettingsRepository,
) {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

    suspend fun export(format: ExportFormat): Result<ExportedFile> = runCatching {
        val report = collect()
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // Only the latest export is kept; older ones were already shared or are stale.
            dir.listFiles()?.forEach { it.delete() }
            val name = "netadmin-inventory-${fileStamp.format(report.generatedAt)}.${format.extension}"
            val file = File(dir, name)
            file.outputStream().use { out ->
                when (format) {
                    ExportFormat.XLSX -> XlsxWriter.write(report.tables, out, report.summary)
                    ExportFormat.PDF -> InventoryPdf.write(report, out)
                }
            }
            ExportedFile(FileProvider.getUriForFile(context, "${context.packageName}.exports", file), format.mime, name)
        }
    }

    private suspend fun collect(): InventoryReport = coroutineScope {
        val s = settings.current()
        val devices = repository.devices().getOrThrow()
        val vlans = async { repository.vlans().getOrDefault(emptyList()) }
        val ports = devices.filter { it.type == DeviceType.SWITCH }
            .map { sw -> async { repository.ports(sw.id).getOrNull()?.let { sw to it } } }
        InventoryReport(
            generatedAt = Instant.now(),
            source = if (s.demoMode) "демо-данные" else s.baseUrl,
            devices = devices,
            vlans = vlans.await(),
            switchPorts = ports.awaitAll().filterNotNull(),
        )
    }
}
