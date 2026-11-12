package com.nativefinds.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports all finds to a CSV (opens in Excel / Google Sheets) and returns a share intent. */
object CsvExporter {
    suspend fun shareIntent(context: Context, db: AppDatabase): Intent {
        val finds = db.finds().getAll()
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "native-finds.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        file.bufferedWriter().use { w ->
            w.appendLine("id,date,category,common_name,scientific_name,confidence,identified_by,status,latitude,longitude,accuracy_m,notes,photo_file")
            finds.forEach { f ->
                w.appendLine(
                    listOf(
                        f.id.toString(), iso.format(Date(f.createdAt)), f.category, f.commonName, f.scientificName,
                        f.confidence?.let { "%.2f".format(Locale.US, it) }, f.idSource, f.establishment,
                        f.latitude?.toString(), f.longitude?.toString(), f.accuracyMeters?.toInt()?.toString(),
                        f.notes, File(f.photoPath).name,
                    ).joinToString(",") { csv(it) },
                )
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Export finds",
        )
    }

    private fun csv(v: String?): String {
        if (v == null) return ""
        val needsQuotes = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}
