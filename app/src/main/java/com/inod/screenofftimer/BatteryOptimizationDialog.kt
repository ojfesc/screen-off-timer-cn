package com.inod.screenofftimer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.inod.screenofftimer.ui.components.ModalDialog

// 系统是否已把本应用排除在电池优化之外。
// 未排除时后台进程会被系统冻结，倒计时会停在最后一个数字，到点也不会执行熄屏
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

// 打开本应用的"应用信息"页，用户在这里关闭电池优化。
// 个别系统没有该页面时退回电池优化列表，用户在列表里选中本应用即可
fun openAppInfoSettings(context: Context) {
    val appInfoIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(appInfoIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // 两个入口都打不开：保持弹窗，用户可自行到系统设置里关闭电池优化
        }
    }
}

// 电池优化门禁：没有豁免就显示不可关闭的弹窗，授予后自动消失
@Composable
fun BatteryOptimizationGate(context: Context) {
    var granted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // 每次回到前台重新检查：从系统页授予后弹窗立刻消失，未授予则继续拦截
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 不显示取消按钮；返回键与点击弹窗外部也不会关闭，只有授予豁免后弹窗才消失
    ModalDialog(
        show = !granted,
        title = stringResource(R.string.dialog_battery_title),
        description = stringResource(R.string.dialog_battery_desc),
        confirmText = stringResource(R.string.action_go),
        dismissText = null,
        onConfirm = { openAppInfoSettings(context) },
        onDismiss = {}
    )
}
