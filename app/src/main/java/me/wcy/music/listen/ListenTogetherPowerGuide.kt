package me.wcy.music.listen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import top.wangchenyan.common.utils.ToastUtils

object ListenTogetherPowerGuide {
    private const val NOTIFICATION_PERMISSION_REQUEST = 2302
    private var promptShownAt = 0L

    fun showIfNeeded(activity: Activity) {
        requestNotificationPermissionIfNeeded(activity)
        if (isIgnoringBatteryOptimizations(activity) || recentlyPrompted()) return
        promptShownAt = System.currentTimeMillis()
        AlertDialog.Builder(activity)
            .setTitle("一起听高可靠模式")
            .setMessage(
                "长时间暂停或锁屏时，系统可能冻结一起听连接。建议把音乐加入电池优化白名单，并在系统管家里允许后台运行/后台联网。一起听退出后，保活服务会立即关闭。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                requestIgnoreBatteryOptimizations(activity)
            }
            .show()
    }

    private fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST
        )
    }

    private fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        }.onFailure {
            openBatteryOptimizationSettings(activity)
        }
    }

    private fun openBatteryOptimizationSettings(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            ToastUtils.show("请在系统设置中允许音乐后台运行")
        }
    }

    private fun openBackgroundSettings(activity: Activity) {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            Intent(Settings.ACTION_SETTINGS)
        )
        intents.firstOrNull { it.resolveActivity(activity.packageManager) != null }?.let {
            activity.startActivity(it)
        } ?: ToastUtils.show("请在系统设置中允许音乐后台运行")
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun recentlyPrompted(): Boolean {
        return System.currentTimeMillis() - promptShownAt < 5 * 60 * 1000L
    }
}
