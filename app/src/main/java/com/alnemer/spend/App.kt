package com.alnemer.spend

import android.app.Application
import com.alnemer.spend.data.Seed
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.alnemer.spend.data.SpendDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // persist any crash so the Tools screen can show it — field debugging without adb
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date()) + "\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
        PDFBoxResourceLoader.init(applicationContext)
        scope.launch { Seed.runIfEmpty(SpendDb.get(this@App)) }
    }
}
