package com.example.allboxtracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

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

        val alarmType = intent.getStringExtra("ALARM_TYPE") ?: "DAILY"
        if (alarmType == "INTERVAL") {
            val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("AUTO_CHECK_ENABLED", true)) {
                val intervalMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)
                AlarmHelper.scheduleIntervalAlarm(context, intervalMin)
            }
        } else {
            val hour = intent.getIntExtra("HOUR", -1)
            val minute = intent.getIntExtra("MINUTE", -1)
            if (hour != -1 && minute != -1) {
                AlarmHelper.scheduleDailyAlarm(context, hour, minute)
            }
        }
    }
}

object AlarmHelper {

    // تنظیم بازه زمانی دلخواه برای بررسی خودکار (مثلاً هر ۱۵، ۳۰، ۶۰ دقیقه و...)
    fun updatePeriodicSchedule(context: Context, enabled: Boolean, intervalMinutes: Int) {
        val safeMinutes = maxOf(5, intervalMinutes)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("AllBoxDiscountTracker")
            cancelIntervalAlarm(context)
            return
        }

        // ۱. تنظیم در WorkManager اندروید (حداقل استاندارد ۱۵ دقیقه)
        val wmMinutes = maxOf(15L, safeMinutes.toLong())
        val work = PeriodicWorkRequestBuilder<DiscountWorker>(wmMinutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AllBoxDiscountTracker",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )

        // ۲. تنظیم آلارم دقیق کمکی برای اجرای دقیق در دقیقه تعیین شده توسط کاربر
        scheduleIntervalAlarm(context, safeMinutes)
    }

    fun scheduleIntervalAlarm(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            putExtra("ALARM_TYPE", "INTERVAL")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + (intervalMinutes * 60 * 1000L)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelIntervalAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleDailyAlarm(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            putExtra("ALARM_TYPE", "DAILY")
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
