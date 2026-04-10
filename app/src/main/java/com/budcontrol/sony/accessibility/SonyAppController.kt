package com.budcontrol.sony.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.budcontrol.sony.protocol.SonyCommands

/**
 * High-level controller that sends commands to the Sony app via:
 *  1. Launch intent (brings Sony app to foreground)
 *  2. AccessibilityService (taps the right button)
 *  3. Returns focus to our app
 */
object SonyAppController {

    private const val TAG = "SonyCtrl"

    fun findSonyPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in SonyAccessibilityService.SONY_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    fun isSonyAppInstalled(context: Context): Boolean = findSonyPackage(context) != null

    fun isAccessibilityEnabled(): Boolean = SonyAccessibilityService.isRunning()

    fun setAncMode(context: Context, mode: SonyCommands.AncMode): Boolean {
        if (!isAccessibilityEnabled()) {
            Log.w(TAG, "Accessibility service not running")
            return false
        }
        val pkg = findSonyPackage(context) ?: return false

        SonyAccessibilityService.requestAction(
            SonyAccessibilityService.PendingAction.SetAnc(mode)
        )
        launchSonyApp(context, pkg)
        return true
    }

    fun readState(context: Context): Boolean {
        if (!isAccessibilityEnabled()) return false
        val pkg = findSonyPackage(context) ?: return false

        SonyAccessibilityService.requestAction(
            SonyAccessibilityService.PendingAction.ReadState
        )
        launchSonyApp(context, pkg)
        return true
    }

    private fun launchSonyApp(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            context.startActivity(intent)
            Log.i(TAG, "Launched Sony app: $pkg")
        } else {
            Log.e(TAG, "Could not get launch intent for $pkg")
        }
    }
}
