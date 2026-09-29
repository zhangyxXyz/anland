package com.anlandnext

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anland.design.*
import com.anlandnext.awl.Awl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Generic explicit activation by a desktop entry's declared window app ID.
 * Keep the caller visible while waiting; only ambiguity/failure needs a dialog.
 * Newly created auto-attached windows are presented by the daemon alone.
 * Never restart clients or change global auto_attach to coordinate a launch.
 */
class OpenWindowActivity : AppCompatActivity() {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var matches by mutableStateOf<List<Awl.WlWindow>>(emptyList())
    private var failed by mutableStateOf(false)
    private var choosing by mutableStateOf(false)
    private var opening = false
    private val callback = object : Awl.Callback {
        override fun onWindowCreated(id: Long, title: String?) { changes.trySend(Unit) }
        override fun onWindowDestroyed(id: Long) { changes.trySend(Unit) }
        override fun onWindowAttached(id: Long) { changes.trySend(Unit) }
        override fun onWindowDetached(id: Long) { changes.trySend(Unit) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        val targets = listOfNotNull(intent.getStringExtra("window_app_id"), intent.getStringExtra("desktop_id"))
            .filter { it.isNotBlank() }.map { it.removeSuffix(".desktop") }
        // The privileged daemon supplies an exact ID, before any document task
        // exists. Both automatic and explicit launches now preload identity.
        val exactId = intent.getLongExtra("id", -1)
        if (targets.isEmpty() && exactId < 0) { finish(); return }
        // An absent snapshot means a legacy caller: it still uses explicit activation.
        val before = intent.getLongArrayExtra("window_ids")?.toSet()
        val automatic = before != null && intent.getBooleanExtra("auto_attach", false)
        val deadline = SystemClock.uptimeMillis() + 20_000
        setContent { WithAnlandTheme(manageSystemBars = false) {
            if (choosing || failed) AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.window_open)) },
                text = { Column {
                    if (failed) Text(stringResource(R.string.window_open_timeout))
                    matches.forEach { w -> NavigationSettingItem(w.title.orEmpty(), onClick = { open(w) }) }
                } },
                confirmButton = { if (failed) TextButton(onClick = {
                    startActivity(android.content.Intent(this, MainActivity::class.java)); finish()
                }) { Text(stringResource(R.string.windows_title)) } },
                dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) } })
            else LaunchStatus(intent.getStringExtra("app_name") ?: stringResource(R.string.window_open)) { finish() }
        } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                changes.trySend(Unit)
                while (isActive && !opening && !failed) {
                    val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)
                    // Once windows are offered, waiting for the user's choice
                    // is not a launch timeout. Keep the list live until chosen
                    // or all its windows disappear.
                    val changed = if (choosing && matches.isNotEmpty()) {
                        changes.receive(); true
                    } else withTimeoutOrNull(remaining) { changes.receive(); true } == true
                    if (!changed) {
                        failed = true
                        break
                    }
                    matches = withContext(Dispatchers.IO) {
                        Awl.getWindows().orEmpty().filter { w ->
                            if (exactId >= 0) return@filter w.id == exactId
                            val id = Awl.applicationId(w.id)?.removeSuffix(".desktop")
                            id != null && targets.any { it.equals(id, ignoreCase = true) }
                        }
                    }
                    if (exactId >= 0 && matches.isEmpty()) { finish(); break }
                    if (matches.size > 1) choosing = true
                    if (matches.size == 1 && !choosing) {
                        val window = matches.single()
                        if (automatic && window.id !in before.orEmpty()) {
                            // Map and attach are separate events. Do not race the
                            // daemon's am start while its host is being created.
                            if (window.attached) { finish(); break }
                        } else { open(window); break }
                    }
                }
            }
        }
    }

    private fun open(window: Awl.WlWindow) {
        if (opening || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        opening = true
        // Identity loading is asynchronous. Keep foreground authorization until
        // the actual startActivity submission, not just until attachWindow returns.
        Awl.attachWindow(this, window, null) { finish() }
    }

    override fun onStart() { super.onStart(); Awl.registerCallback(callback) }
    override fun onStop() {
        Awl.unregisterCallback(callback)
        super.onStop()
        // A native auto-launch or the user's Home/Back action owns focus now.
        // A stale waiter must never reopen that window or show a later error.
        if (!isChangingConfigurations) finish()
    }
    override fun finish() { super.finish(); overridePendingTransition(0, 0) }
}
