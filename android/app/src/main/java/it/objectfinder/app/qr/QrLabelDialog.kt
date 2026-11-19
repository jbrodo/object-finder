package it.objectfinder.app.qr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import java.io.File

@Composable
fun QrLabelDialog(objectId: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val label = remember(objectId, title) { QrCodes.labelBitmap(objectId, title.ifBlank { "Oggetto" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Etichetta QR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    bitmap = label.asImageBitmap(),
                    contentDescription = "Codice QR dell'oggetto $title",
                    filterQuality = FilterQuality.None, // moduli nitidi
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
                )
                Text(
                    "Stampala e applicala all'oggetto: scansionandola dall'app (o con la fotocamera " +
                        "del telefono) si apre la sua scheda. Il codice contiene solo un identificativo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { printLabel(context, label, title) }) {
                Icon(Icons.Default.Print, contentDescription = null)
                Text("  Stampa")
            }
        },
        dismissButton = {
            TextButton(onClick = { shareLabel(context, label, objectId, title) }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text("  Condividi")
            }
        },
    )
}

private fun printLabel(context: Context, label: Bitmap, title: String) {
    PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
        .printBitmap("Etichetta $title", label)
}

private fun shareLabel(context: Context, label: Bitmap, objectId: String, title: String) {
    val dir = File(context.cacheDir, "labels").apply { mkdirs() }
    val file = File(dir, "etichetta-$objectId.png")
    file.outputStream().use { label.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "Etichetta $title")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Condividi etichetta"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Nessuna app disponibile per la condivisione", Toast.LENGTH_SHORT).show()
    }
}
