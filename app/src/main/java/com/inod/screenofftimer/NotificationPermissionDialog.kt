package com.inod.screenofftimer

import android.app.Activity
import android.content.Context
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
import com.inod.screenofftimer.core.permission.NotificationPermission
import com.inod.screenofftimer.ui.components.ModalDialog

// 通知权限门禁：未授权时显示不可关闭的弹窗，授予后自动消失。
// 电池优化未处理时先不显示，保证同一时刻只有一个门禁弹窗
@Composable
fun NotificationPermissionGate(context: Context) {
    val activity = context as Activity
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(activity)) }
    var batteryGranted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // 每次回到前台重新检查：从系统页授予后弹窗立刻消失，未授予则继续拦截
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationPermission.isGranted(activity)
                batteryGranted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 不显示取消按钮；返回键与点击弹窗外部也不会关闭，只有授予权限后弹窗才消失
    ModalDialog(
        show = !granted && batteryGranted,
        title = stringResource(R.string.dialog_notif_permission_title),
        description = stringResource(R.string.dialog_notif_permission_desc),
        confirmText = stringResource(R.string.action_go),
        dismissText = null,
        onConfirm = { NotificationPermission.openSettings(activity) },
        onDismiss = {}
    )
}
