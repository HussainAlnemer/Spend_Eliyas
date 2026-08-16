package com.alnemer.spend.ingest

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alnemer.spend.data.SourceKind
import com.alnemer.spend.data.SpendDb
import com.alnemer.spend.parse.ParseResult
import com.alnemer.spend.parse.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Automatic capture: SMS and app notifications become ledger entries in real time. */
class NotifListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bankMarkers = listOf("SAB", "barq", "EmiratesNBD", "NBD", "Mobily", "stc", "Riyad")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = (extras.getCharSequence("android.bigText")
            ?: extras.getCharSequence("android.text"))?.toString() ?: return
        val fromBank = bankMarkers.any { title.contains(it, true) }
        val (_, r) = ParserRegistry.parse(title, text)
        val relevant = fromBank || r is ParseResult.Transaction || r is ParseResult.Ignored
        if (!relevant) return
        scope.launch {
            try {
                val db = SpendDb.get(applicationContext)
                Ingestor(db).ingest(sender = title, body = text, source = SourceKind.PUSH)
                Matcher(db).run()
            } catch (_: Exception) { }
        }
    }
}
