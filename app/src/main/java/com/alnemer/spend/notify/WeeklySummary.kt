package com.alnemer.spend.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alnemer.spend.R
import com.alnemer.spend.data.SpendDb
import com.alnemer.spend.fmt
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "weekly_summary"
private const val CHANNEL_ID = "weekly_summary"

/**
 * Friday 8 PM by default — the end of the Saudi work/school week (weekend is Fri-Sat), not
 * Sunday. Ask if a different day/time is wanted; this is a one-line change in this function.
 */
private fun millisUntilNextFridayEvening(): Long {
    val now = Calendar.getInstance()
    val target = now.clone() as Calendar
    target.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
    target.set(Calendar.HOUR_OF_DAY, 20); target.set(Calendar.MINUTE, 0)
    target.set(Calendar.SECOND, 0); target.set(Calendar.MILLISECOND, 0)
    if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 7)
    return target.timeInMillis - now.timeInMillis
}

/** Idempotent — safe to call every time the toggle is turned on, re-syncs the schedule. */
fun scheduleWeeklySummary(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
        .setInitialDelay(millisUntilNextFridayEvening(), TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
}

fun cancelWeeklySummary(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
}

/** Runs weekly in the background (no app open needed) and posts one notification: gained,
 *  spent, and net for the trailing 7 days. Uses trueSpendBetween/strictIncomeBetween — the same
 *  totals Home itself shows — so this always matches what's on screen. */
class WeeklySummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val db = SpendDb.get(applicationContext)
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val spend = db.txns().trueSpendBetween(weekAgo, now)
        val income = db.txns().strictIncomeBetween(weekAgo, now)
        val net = income - spend
        val netStr = if (net < 0) "-" + fmt(-net) else fmt(net)

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val hasPermission = Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Weekly summary: $netStr SAR net")
                .setContentText("Gained ${fmt(income)} · Spent ${fmt(spend)} SAR")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notif)
        }
        return Result.success()
    }
}
