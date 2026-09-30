package com.anland.design

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors

/** Publish policy, not a snapshot: System continues following Android while both UIs sleep. */
class AppearanceApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preferences: SharedPreferences
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode") publish(false)
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences("anland_appearance", MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        registerActivityLifecycleCallbacks(this)
    }

    private fun publish(claim: Boolean) {
        val mode = preferences.getString("mode", "System") ?: "System"
        worker.execute {
            runCatching {
                val result = contentResolver.call(Uri.parse("content://com.anland.shell.appearance"),
                    "publish", mode, Bundle().apply { putBoolean("claim", claim) })
                check(result?.getBoolean("ok") == true) { "Appearance bridge response: $result" }
            }.onFailure { Log.w("AnlandAppearance", "Cannot synchronize Linux appearance", it) }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // Opening/focusing Linux windows is not a change of the controlling Android UI.
        // Otherwise mapping a window can replace Shell's policy with Wayland's policy.
        publish(LinuxThemePolicy.claimsOnResume(activity.javaClass.simpleName))
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (preferences.getString("mode", "System") == "System") publish(false)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
