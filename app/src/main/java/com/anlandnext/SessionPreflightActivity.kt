package com.anlandnext

import com.anland.design.suppressLocaleTransition
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Some Android vendors block a provider from starting a stopped host process.
 * A user-initiated, transparent Activity can return the same read-only snapshot
 * without opening the window list or changing background-start permissions.
 */
class SessionPreflightActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressLocaleTransition()
        // An explicit result request identifies the caller; Intent extras cannot.
        if (callingPackage != "com.anland.shell") { finish(); return }
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { readSessionWindows() }
                setResult(Activity.RESULT_OK, Intent().putExtras(snapshot))
            } catch (e: Exception) {
                setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", e.message))
            }
            finish()
        }
    }

    override fun finish() { super.finish(); suppressLocaleTransition() }
}
