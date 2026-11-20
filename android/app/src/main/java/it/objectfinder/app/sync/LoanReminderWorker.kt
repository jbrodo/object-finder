package it.objectfinder.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.objectfinder.app.MainActivity
import it.objectfinder.app.ObjectFinderApplication
import it.objectfinder.app.R
import it.objectfinder.app.qr.QrCodes
import it.objectfinder.app.ui.objects.LoanFormat
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Promemoria dei prestiti: una volta al giorno avvisa degli oggetti da restituire oggi o già
 * scaduti (una notifica per oggetto e giorno). Toccandola si apre la scheda dell'oggetto.
 */
class LoanReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!canNotify(context)) return Result.success()
        val container = (context as ObjectFinderApplication).container
        val today = LocalDate.now()
        val prefs = context.getSharedPreferences("loan_reminders", Context.MODE_PRIVATE)
        ensureChannel(context)
        container.objectRepository.loansDueBy(today).forEach { item ->
            val key = "${item.id}:$today"
            if (prefs.getBoolean(key, false)) return@forEach
            val due = item.loanDue?.let(LocalDate::parse) ?: return@forEach
            val open = PendingIntent.getActivity(
                context, item.id.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(QrCodes.payloadFor(item.id)), context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (due.isBefore(today)) "Prestito scaduto: ${item.name}" else "Da restituire oggi: ${item.name}")
                .setContentText("Prestato a ${item.loanedTo} · ${LoanFormat.due(due, today)}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // Permesso ricontrollato qui: l'utente può revocarlo mentre il lavoro è in corso.
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return Result.success()
            try {
                NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
            } catch (_: SecurityException) {
                return Result.success()
            }
            prefs.edit().putBoolean(key, true).apply()
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "loans"
        private const val WORK = "loan-reminders"

        /** Controllo giornaliero alle 9 (non all'ora in cui l'app è stata installata). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LoanReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNext(REMIND_HOUR), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Controllo subito (apertura dell'app, prestito salvato): un prestito già scaduto avvisa
         * senza aspettare il giorno dopo. Al massimo una notifica per oggetto e giorno.
         */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<LoanReminderWorker>().build(),
            )
        }

        private const val REMIND_HOUR = 9

        /** Millisecondi fino alle [hour]:00 di oggi, o di domani se già passate. */
        fun millisUntilNext(hour: Int, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Long {
            var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return java.time.Duration.between(now, next).toMillis()
        }

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Prestiti", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Promemoria degli oggetti prestati da restituire"
                    }
                )
            }
        }
    }
}
