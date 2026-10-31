package com.example.allboxtracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar

class DiscountWorker(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val (success, _) = DiscountEngine.checkPricesNow(context, isManual = false)
        return if (success) Result.success() else Result.retry()
    }
}

class ExactTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = OneTimeWorkRequestBuilder<DiscountWorker>().build()
        WorkManager.getInstance(context).enqueue(work)

        val hour = intent.getIntExtra("HOUR", -1)
        val minute = intent.getIntExtra("MINUTE", -1)
        if (hour != -1 && minute != -1) {
            AlarmHelper.scheduleDailyAlarm(context, hour, minute)
        }
    }
}

object AlarmHelper {
    fun scheduleDailyAlarm(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            putExtra("HOUR", hour)
            putExtra("MINUTE", minute)
        }
        val requestCode = hour * 100 + minute
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }

    fun cancelDailyAlarm(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java)
        val requestCode = hour * 100 + minute
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
