package com.example.allboxtracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val alarmType = intent.getStringExtra("ALARM_TYPE") ?: "DAILY"
        val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)

        // ۱. اگر آلارم مربوط به تایمر دوره‌ای است، فقط تایمر دوره‌ای بعدی را تنظیم کن
        if (alarmType == "INTERVAL") {
            if (prefs.getBoolean("AUTO_CHECK_ENABLED", true)) {
                val intervalMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)
                AlarmHelper.scheduleIntervalAlarm(context, intervalMin, forceReset = true)
            }
        }
        // ۲. اگر آلارم مربوط به ساعت‌های جداگانه روزانه است، کاملاً مستقل از تایمر دوره‌ای اجرا شو و برای فردا تنظیم کن
        else if (alarmType == "DAILY") {
            val hour = intent.getIntExtra("HOUR", -1)
            val minute = intent.getIntExtra("MINUTE", -1)
            if (hour != -1 && minute != -1) {
                AlarmHelper.scheduleDailyAlarm(context, hour, minute)
            }
        }

        // اجرای فوری بررسی قیمت‌ها (حتی اگر کمتر از زمان تایمر دوره‌ای از آخرین بررسی گذشته باشد)
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AllBoxTracker::BgCheckWakeLock"
        )
        wakeLock.acquire(3 * 60 * 1000L)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiscountEngine.checkPricesNow(context, isManual = false)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmHelper.restoreAllSchedules(context)
        }
    }
}

object AlarmHelper {

    fun restoreAllSchedules(context: Context) {
        val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("AUTO_CHECK_ENABLED", true)
        val intervalMin = prefs.getInt("AUTO_INTERVAL_MINUTES", 30)
        // عدم ریست کردن تایمر در صورت باز شدن معمولی برنامه
        updatePeriodicSchedule(context, enabled, intervalMin, forceReset = false)

        val savedTimes = prefs.getStringSet("CUSTOM_TIMES", emptySet()) ?: emptySet()
        for (timeStr in savedTimes) {
            val parts = timeStr.split(":")
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull() ?: continue
                val m = parts[1].toIntOrNull() ?: continue
                scheduleDailyAlarm(context, h, m)
            }
        }
    }

    fun updatePeriodicSchedule(context: Context, enabled: Boolean, intervalMinutes: Int, forceReset: Boolean = true) {
        val safeMinutes = maxOf(1, intervalMinutes)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("AllBoxDiscountTracker")
            cancelIntervalAlarm(context)
            return
        }

        val wmMinutes = maxOf(15L, safeMinutes.toLong())
        val work = PeriodicWorkRequestBuilder<DiscountWorker>(wmMinutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AllBoxDiscountTracker",
            if (forceReset) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            work
        )

        scheduleIntervalAlarm(context, safeMinutes, forceReset = forceReset)
    }

    fun scheduleIntervalAlarm(context: Context, intervalMinutes: Int, forceReset: Boolean = true) {
        val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val savedNextTrigger = prefs.getLong("NEXT_INTERVAL_TRIGGER_MS", 0L)

        val triggerAt = if (!forceReset && savedNextTrigger > now + 5000L) {
            savedNextTrigger
        } else {
            val next = now + (intervalMinutes * 60 * 1000L)
            prefs.edit().putLong("NEXT_INTERVAL_TRIGGER_MS", next).apply()
            next
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            action = "com.example.allboxtracker.ACTION_INTERVAL_ALARM"
            putExtra("ALARM_TYPE", "INTERVAL")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setHighPriorityAlarm(context, alarmManager, triggerAt, pendingIntent)
    }

    private fun cancelIntervalAlarm(context: Context) {
        val prefs = context.getSharedPreferences("AllBoxPrefs", Context.MODE_PRIVATE)
        prefs.edit().remove("NEXT_INTERVAL_TRIGGER_MS").apply()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            action = "com.example.allboxtracker.ACTION_INTERVAL_ALARM"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 99999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // تنظیم ساعت‌های روزانه با شناسه کاملاً مستقل تا حتی در فواصل کمتر از تایمر اصلی هم اجرا شوند
    fun scheduleDailyAlarm(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = 10000 + (hour * 100) + minute
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            action = "com.example.allboxtracker.ACTION_DAILY_ALARM_$requestCode"
            putExtra("ALARM_TYPE", "DAILY")
            putExtra("HOUR", hour)
            putExtra("MINUTE", minute)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        setHighPriorityAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
    }

    fun cancelDailyAlarm(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = 10000 + (hour * 100) + minute
        val intent = Intent(context, ExactTimeReceiver::class.java).apply {
            action = "com.example.allboxtracker.ACTION_DAILY_ALARM_$requestCode"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun setHighPriorityAlarm(
        context: Context,
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        operation: PendingIntent
    ) {
        try {
            val showIntent = Intent(context, MainActivity::class.java)
            val showPending = PendingIntent.getActivity(
                context, 0, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPending)
            alarmManager.setAlarmClock(clockInfo, operation)
        } catch (e: Exception) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            } catch (_: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            }
        }
    }
}
