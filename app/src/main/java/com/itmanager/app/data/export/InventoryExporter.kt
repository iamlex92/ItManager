package com.itmanager.app.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.itmanager.app.core.network.NetAdminApi
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

/** [serverPath] set: the file is built by the server (NetBox CSV archive, printable QR labels) and only fetched here. */
enum class ExportFormat(val label: String, val extension: String, val mime: String, val serverPath: String? = null) {
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF", "pdf", "application/pdf"),
    NETBOX("NetBox", "zip", "application/zip", "export/netbox.zip"),
    LABELS("Наклейки", "html", "text/html", "export/labels.html"),
}

data class ExportedFile(val uri: Uri, val mime: String, val name: String)

/**
 * Builds the inventory report from the current data source (the server) and writes it to the app's
 * cache, from where the share sheet hands it to mail, a messenger or Files. Reads only.
 */
@Singleton
class InventoryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DcimRepository,
    private val settings: SettingsRepository,
    private val api: NetAdminApi,
) {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

    suspend fun export(format: ExportFormat): Result<ExportedFile> = runCatching {
        val serverPath = format.serverPath
        if (serverPath != null) return@runCatching exportFromServer(format, serverPath)
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
                    // Built by the server and returned before this point.
                    ExportFormat.NETBOX, ExportFormat.LABELS -> error("server-built format")
                }
            }
            ExportedFile(FileProvider.getUriForFile(context, "${context.packageName}.exports", file), format.mime, name)
        }
    }

    private suspend fun exportFromServer(format: ExportFormat, path: String): ExportedFile {
        val bytes = api.download(settings.current(), path)
        return save("itmanager-${format.name.lowercase()}-${fileStamp.format(Instant.now())}.${format.extension}", format.mime, bytes)
    }

    /** The domain computers' licence keys (Windows, Office, 1C) as the server built them: CSV for Excel. */
    suspend fun keysInventory(jobId: String): Result<ExportedFile> = runCatching {
        val bytes = repository.pcKeysCsv(jobId).getOrThrow()
        save("Keys_Inventory_${fileStamp.format(Instant.now())}.csv", "text/csv", bytes)
    }

    private suspend fun save(name: String, mime: String, bytes: ByteArray): ExportedFile = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        ExportedFile(FileProvider.getUriForFile(context, "${context.packageName}.exports", file), mime, name)
    }

    private suspend fun collect(): InventoryReport = coroutineScope {
        val s = settings.current()
        val devices = repository.devices().getOrThrow()
        val vlans = async { repository.vlans().getOrDefault(emptyList()) }
        val ports = devices.filter { it.type == DeviceType.SWITCH }
            .map { sw -> async { repository.ports(sw.id).getOrNull()?.let { sw to it } } }
        InventoryReport(
            generatedAt = Instant.now(),
            source = s.baseUrl,
            devices = devices,
            vlans = vlans.await(),
            switchPorts = ports.awaitAll().filterNotNull(),
        )
    }
}
