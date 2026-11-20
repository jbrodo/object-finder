package it.objectfinder.app.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import it.objectfinder.app.data.SettingsStore
import it.objectfinder.app.data.local.IdentifierDao
import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.PhotoDao
import it.objectfinder.app.data.local.PhotoSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.LocalDate

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    CSV("Excel (CSV)", "csv", "text/csv"),
    PDF("PDF da stampare", "pdf", "application/pdf"),
}

/** Raccoglie l'inventario del progetto attivo e lo scrive in CSV o PDF. */
class InventoryExporter(
    context: Context,
    private val objects: ObjectDao,
    private val identifiers: IdentifierDao,
    private val photos: PhotoDao,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val exportDir = File(appContext.cacheDir, "exports")

    val projectName: String get() = settings.activeProject.value?.name ?: "Archivio locale"

    suspend fun rows(includeArchived: Boolean): List<InventoryRow> = withContext(Dispatchers.IO) {
        val projectId = settings.activeProject.value?.id ?: LOCAL_PROJECT_ID
        val codes = identifiers.allForProject(projectId)
            .filter { it.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire }
            .groupBy { it.objectId }
        val pics = photos.allForProject(projectId)
            .filter { it.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire }
            .sortedBy { it.createdAt }
            .groupBy { it.objectId }
        objects.allByProject(projectId)
            .filter { includeArchived || it.statusEnum != ObjectStatus.ARCHIVED }
            .map { item ->
                InventoryRow(
                    item = item,
                    codes = codes[item.id].orEmpty().map { InventoryCsv.codeLabel(it.typeEnum, it.value) },
                    photoCount = pics[item.id].orEmpty().size,
                    coverPath = pics[item.id].orEmpty().firstOrNull { it.localPath != null }?.localPath,
                )
            }
    }

    fun fileName(format: ExportFormat): String {
        val slug = projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "inventario" }
        return "inventario-$slug-${LocalDate.now()}.${format.extension}"
    }

    suspend fun writeTo(out: OutputStream, format: ExportFormat, rows: List<InventoryRow>) = withContext(Dispatchers.IO) {
        when (format) {
            ExportFormat.CSV -> out.write(InventoryCsv.build(rows).toByteArray(Charsets.UTF_8))
            ExportFormat.PDF -> InventoryPdf.write(projectName, rows, out)
        }
    }

    /** File temporaneo condivisibile (FileProvider, cartella cache/exports). */
    suspend fun exportToShareableFile(format: ExportFormat, includeArchived: Boolean): Pair<Uri, Int> {
        val rows = rows(includeArchived)
        val file = withContext(Dispatchers.IO) {
            exportDir.mkdirs()
            exportDir.listFiles()?.forEach { it.delete() } // solo l'ultimo export resta in cache
            File(exportDir, fileName(format))
        }
        file.outputStream().use { writeTo(it, format, rows) }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return uri to rows.size
    }
}
