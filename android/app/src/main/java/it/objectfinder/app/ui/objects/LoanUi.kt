package it.objectfinder.app.ui.objects

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import it.objectfinder.app.sync.LoanReminderWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Riquadro "In prestito" nella scheda: a chi, da quando, scadenza, "Restituito". */
@Composable
fun LoanPanel(
    loanedTo: String,
    loanedAt: Long?,
    due: LocalDate?,
    readOnly: Boolean,
    onReturned: () -> Unit,
    onEdit: () -> Unit,
) {
    val today = LocalDate.now()
    val overdue = LoanFormat.isOverdue(due, today)
    Surface(
        color = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Handshake, contentDescription = null, Modifier.size(18.dp))
                Text("In prestito", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            }
            Text("a $loanedTo", style = MaterialTheme.typography.titleLarge)
            val since = loanedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            Text(
                listOfNotNull(since?.let { "dal ${LoanFormat.date(it)}" }, due?.let { LoanFormat.due(it, today) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pulsanti nei colori del riquadro (ambra o rosso), non nel blu/ardesia generici.
                val scheme = MaterialTheme.colorScheme
                Button(
                    onClick = onReturned,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (overdue) scheme.error else scheme.tertiary,
                        contentColor = if (overdue) scheme.onError else scheme.onTertiary,
                    ),
                ) { Text("Restituito") }
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) { Text("Modifica") }
            }
        }
    }
}

/**
 * "Presta…": a chi (con i nomi già usati) e scadenza facoltativa. Con una scadenza si chiede il
 * permesso di notifica (Android 13+), per il promemoria del giorno stabilito.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LendDialog(
    initialName: String,
    initialDue: LocalDate?,
    borrowers: List<String>,
    onConfirm: (String, LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var due by remember { mutableStateOf(initialDue) }
    var picking by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun confirm() {
        if (name.isBlank()) return
        if (due != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LoanReminderWorker.canNotify(context)) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onConfirm(name.trim(), due)
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: today.plusWeeks(1)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Annulla") } },
        ) { DatePicker(state = state) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Handshake, contentDescription = null) },
        title = { Text(if (initialName.isBlank()) "Presta l'oggetto" else "Modifica prestito") },
        text = {
            // Scorrevole: nome, suggerimenti e cinque scelte non entrano su uno schermo basso.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    label = { Text("A chi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                val suggestions = borrowers.filter { it != name && (name.isBlank() || it.contains(name.trim(), ignoreCase = true)) }.take(4)
                if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { SuggestionChip(onClick = { name = it }, label = { Text(it) }) }
                }
                Text("Da restituire", style = MaterialTheme.typography.labelLarge)
                DueDateOptions(due = due, today = today, onSelect = { due = it }, onPickDate = { picking = true })
                if (due != null) Text(
                    "Il giorno della scadenza ti ricordo di farlo restituire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = ::confirm, enabled = name.isNotBlank()) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/**
 * Scadenza del prestito: una scelta sola, quindi pulsanti di opzione (righe da 48dp) e non chip
 * combinabili. Ogni scorciatoia mostra anche la data esatta.
 */
@Composable
internal fun DueDateOptions(
    due: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate?) -> Unit,
    onPickDate: () -> Unit,
) {
    val presets = listOf(
        "Tra 1 settimana" to today.plusWeeks(1),
        "Tra 2 settimane" to today.plusWeeks(2),
        "Tra 1 mese" to today.plusMonths(1),
    )
    val custom = due?.takeIf { d -> presets.none { it.second == d } }
    Column(Modifier.selectableGroup()) {
        DueOption("Nessuna data", selected = due == null) { onSelect(null) }
        presets.forEach { (label, day) ->
            DueOption("$label · ${LoanFormat.date(day)}", selected = due == day) { onSelect(day) }
        }
        DueOption(custom?.let { "Il ${LoanFormat.date(it)} · cambia" } ?: "Scegli una data…", selected = custom != null, onClick = onPickDate)
    }
}

@Composable
private fun DueOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
