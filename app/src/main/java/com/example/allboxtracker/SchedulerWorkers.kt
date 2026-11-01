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

        // ۱. تنظیم مجدد آلارم بعدی بلافاصله
        if (alarmType == "INTERVAL") {
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

        // ۲. اجرای مستقیم و فوری بررسی قیمت‌ها بدون معطل ماندن در صف WorkManager
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AllBoxTracker::BgCheckWakeLock"
        )
        wakeLock.acquire(3 * 60 * 1000L) // بیدار نگه داشتن پردازنده حداکثر تا ۳ دقیقه

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

// راه‌اندازی مجدد تمام تایمرها پس از خاموش و روشن شدن گوشی
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
        updatePeriodicSchedule(context, enabled, intervalMin)

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

    fun updatePeriodicSchedule(context: Context, enabled: Boolean, intervalMinutes: Int) {
        val safeMinutes = maxOf(5, intervalMinutes)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("AllBoxDiscountTracker")
            cancelIntervalAlarm(context)
            return
        }

        val wmMinutes = maxOf(15L, safeMinutes.toLong())
        val work = PeriodicWorkRequestBuilder<DiscountWorker>(wmMinutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AllBoxDiscountTracker",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )

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
        setHighPriorityAlarm(context, alarmManager, triggerAt, pendingIntent)
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
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        setHighPriorityAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
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

    // استفاده از AlarmClock برای جلوگیری ۱۰۰٪ از توقف تایمر در حالت استندبای و Doze اندروید
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
