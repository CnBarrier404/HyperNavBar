package com.ianzb.hypernavbar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ianzb.hypernavbar.ui.screen.rules.FloatingIdentifyService

class AppIdentifyAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_FOREGROUND_CHANGED = "com.ianzb.hypernavbar.FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
        const val EXTRA_APP_NAME = "app_name"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        val className = event.className?.toString()

        if (packageName.isNullOrEmpty()) return
        if (packageName == this.packageName) return

        // Filter out view class names — only keep actual Activity/Fragment class names
        val activityName = if (className != null && !isViewClassName(className)) className else ""

        val appName = resolveAppName(packageName)

        Log.d("HyperNavBar", "Accessibility: pkg=$packageName cls=$className→$activityName app=$appName")

        // Direct callback (same process) — more reliable than broadcasts
        FloatingIdentifyService.notifyForegroundApp(packageName, appName, activityName)

        // Also send broadcast for any other listeners
        val intent = Intent(ACTION_FOREGROUND_CHANGED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_APP_NAME, appName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sendBroadcast(intent, null)
        } else {
            @Suppress("DEPRECATION")
            sendBroadcast(intent)
        }
    }

    override fun onInterrupt() {
        // No action needed
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.MATCH_ALL
            } else {
                0
            }
            val appInfo = packageManager.getApplicationInfo(packageName, flags)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /**
     * Accessibility events sometimes carry view class names (FrameLayout,
     * TextureView, etc.) instead of the Activity class. Filter them out.
     */
    private fun isViewClassName(className: String): Boolean {
        if (className.isEmpty()) return true
        // Android framework view packages
        val viewPrefixes = listOf(
            "android.view.", "android.widget.", "android.webkit.",
            "android.app.", "com.android.internal.",
        )
        for (prefix in viewPrefixes) {
            if (className.startsWith(prefix)) {
                // Exceptions: activity classes under android.app
                if (prefix == "android.app.") {
                    // Allow android.app.Activity subclasses but not android.app.Dialog etc.
                    // Heuristic: if it contains a dot after android.app., skip
                    val afterPrefix = className.removePrefix("android.app.")
                    if (afterPrefix.contains(".")) return true // e.g. android.app.Dialog$xxx
                    // Single-segment = likely a top-level Activity class like android.app.Activity
                    // Skip those too since they're not the actual Activity name
                    return true
                }
                return true
            }
        }
        // No package prefix at all = single class name like "MainActivity" or "FrameLayout"
        if (!className.contains(".") && className.isNotEmpty()) {
            // Heuristic: known view class names
            val knownViews = setOf(
                "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout",
                "TextView", "ImageView", "Button", "EditText", "ViewGroup",
                "TextureView", "SurfaceView", "RecyclerView", "ScrollView",
                "DecorView", "ContentFrameLayout", "ActionBarOverlayLayout",
            )
            if (className in knownViews) return true
        }
        return false
    }
}
