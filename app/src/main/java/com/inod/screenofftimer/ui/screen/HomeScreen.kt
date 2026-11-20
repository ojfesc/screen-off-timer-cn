package com.inod.screenofftimer.ui.screen

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.inod.screenofftimer.R
import com.inod.screenofftimer.core.permission.AccessibilityPermission
import com.inod.screenofftimer.core.permission.NotificationPermission
import com.inod.screenofftimer.service.isDpmActive
import com.inod.screenofftimer.service.requestDeviceAdmin
import com.inod.screenofftimer.ui.components.ModalDialog
import com.inod.screenofftimer.ui.components.SwitchStyle
import com.inod.screenofftimer.ui.components.settings.ListOption
import com.inod.screenofftimer.ui.components.settings.ListSection
import com.inod.screenofftimer.viewmodel.TimerViewModel

// 弹窗当前编辑的时间单位
private enum class TimeField { HOUR, MINUTE }

// 单个输入框允许的最大值，保证换算后的总秒数不超过 Int 上限
private const val MAX_HOURS = 596_523L
private const val MAX_MINUTES = 35_791_394L

// 时间区域的固定尺寸：数字槽、单位文字宽度、整行宽度
// 三行使用同一行宽，保证小时、分钟、秒的数字中心落在同一条竖线上
private val TIME_SLOT_WIDTH = 200.dp
private val TIME_LABEL_WIDTH = 88.dp
private val TIME_ROW_WIDTH = 300.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    context: Context, viewModel: TimerViewModel, onOpenSettings: () -> Unit
) {

    val activity = context as Activity

    val settings by viewModel.allSettings.collectAsState()

    // 运行状态与两个开关的当前值
    val isRunning = settings.isRunning
    val isStopMedia = settings.isStopMedia
    val lockScreenEnabled = viewModel.isLockScreen

    // 倒计时总秒数：运行时由服务每秒广播刷新，空闲时来自本地配置
    val totalSeconds = viewModel.leftSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 回到前台时读取系统里的真实权限状态，避免界面开关与系统设置不一致
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val a11y = isAccessibilityEnabled(context)
                val dpmActive = isDpmActive(context)
                viewModel.syncLockPermissions(a11y, dpmActive)

                val granted = isNotifGranted(activity)
                if (viewModel.isNotifPermission != granted) {
                    viewModel.updateNotifPermission(granted)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 页面可见时接收倒计时广播，并与前台服务同步当前计时状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.registerReceiver(context)
                    viewModel.reconcileTimerState()
                }

                Lifecycle.Event.ON_STOP -> {
                    viewModel.unregisterReceiver(context)
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showNotifPermissionDialog by remember { mutableStateOf(false) }
    var showLockMethodDialog by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<TimeField?>(null) }

    // 写入新的总秒数，并同步“上次时长”，倒计时结束后可恢复到同一时长
    val applyTotalSeconds: (Int) -> Unit = { value ->
        val safeValue = value.coerceAtLeast(0)
        viewModel.updateLeftSeconds(safeValue)
        viewModel.saveLastDrag(safeValue / 60)
    }

    // 播放键：运行中停止，空闲时校验时长与通知权限后启动
    val handleStartStop: () -> Unit = let@{
        if (isRunning) {
            viewModel.stop(totalSeconds)
            return@let
        }

        if (totalSeconds <= 0) {
            return@let
        }

        viewModel.startTimer(totalSeconds)
        viewModel.saveLastDrag(totalSeconds / 60)

        val granted = isNotifGranted(activity)
        if (!granted) {
            if (viewModel.isNoShowNotifPermission) {
                showToastProperly(context)
            } else {
                showNotifPermissionDialog = true
            }
            return@let
        }

        if (settings.isAutoCloseOnStart) {
            activity.moveTaskToBack(true)
        }
    }

    // 重置键：停止计时并把时间清空为默认的空
    val handleReset: () -> Unit = {
        if (isRunning) {
            viewModel.stop(totalSeconds)
        }
        applyTotalSeconds(0)
    }

    val handleNotShowAgain: () -> Unit = {
        viewModel.updateNoShowNotifPermission(true)
        showNotifPermissionDialog = false
        showToastProperly(context)
    }

    // 打开锁屏开关时，两种锁屏方式都未授权则让用户选择一种
    val handleToggleLockscreen: (Boolean) -> Unit = { value ->
        viewModel.updateLockScreen(value)
        if (value) {
            val dpmActive = isDpmActive(context)
            val accessibilityActive = isAccessibilityEnabled(context)
            if (!dpmActive && !accessibilityActive) {
                showLockMethodDialog = true
            }
        }
    }

    // 两个按钮使用同一组配色：运行时为停止色，空闲时为主色
    val buttonContainerColor = if (isRunning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val buttonContentColor = if (isRunning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            modifier = Modifier.size(25.dp),
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 小时输入框：空值显示 0，点击后在弹窗内输入，运行中不可修改
            TimeValueRow(
                value = hours.toString(),
                unit = stringResource(R.string.home_hour_unit),
                enabled = !isRunning,
                onClick = { editingField = TimeField.HOUR }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 分钟输入框：空值显示 0，点击后在弹窗内输入，运行中不可修改
            TimeValueRow(
                value = minutes.toString(),
                unit = stringResource(R.string.home_min_unit),
                enabled = !isRunning,
                onClick = { editingField = TimeField.MINUTE }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 秒读数：随倒计时每秒变化，数字与上方两个输入框中心对齐
            Row(
                modifier = Modifier.width(TIME_ROW_WIDTH),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(TIME_SLOT_WIDTH),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = seconds.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_second_unit),
                    modifier = Modifier.width(TIME_LABEL_WIDTH),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 重置键：停止计时并清空小时与分钟
                FilledIconButton(
                    onClick = handleReset,
                    enabled = isRunning || totalSeconds > 0,
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = buttonContainerColor,
                        contentColor = buttonContentColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.action_reset),
                        modifier = Modifier.size(40.dp)
                    )
                }

                // 播放/停止键：与重置键同尺寸同配色，运行中切换为停止图标
                FilledIconButton(
                    onClick = { handleStartStop() },
                    enabled = isRunning || totalSeconds > 0,
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = buttonContainerColor,
                        contentColor = buttonContentColor
                    )
                ) {
                    AnimatedContent(targetState = isRunning, label = "icon_anim") { running ->
                        Icon(
                            imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                            modifier = Modifier.size(40.dp),
                            contentDescription = null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            ListSection {
                ListOption(
                    title = stringResource(R.string.option_turn_off_music_title),
                    description = stringResource(R.string.option_turn_off_music_desc),
                    enabled = !isRunning,
                    icon = Icons.Default.MusicOff,
                    onClick = { viewModel.updateStopMedia(!isStopMedia) },
                    trailing = {
                        SwitchStyle(
                            checked = isStopMedia,
                            enabled = !isRunning,
                            onCheckedChange = { viewModel.updateStopMedia(it) }
                        )
                    })
                ListOption(
                    title = stringResource(R.string.option_lock_screen_title),
                    description = stringResource(R.string.option_lock_screen_desc),
                    enabled = !isRunning,
                    icon = Icons.Default.Lock,
                    onClick = { handleToggleLockscreen(!lockScreenEnabled) },
                    trailing = {
                        SwitchStyle(
                            checked = lockScreenEnabled,
                            enabled = !isRunning,
                            onCheckedChange = handleToggleLockscreen
                        )
                    })
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 通知权限弹窗
        ModalDialog(
            show = showNotifPermissionDialog,
            title = stringResource(R.string.dialog_notif_permission_title),
            description = stringResource(R.string.dialog_notif_permission_desc),
            confirmText = stringResource(R.string.action_agree),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showNotifPermissionDialog = false
                openNotifSetting(activity)
            },
            onDismiss = {
                showNotifPermissionDialog = false
                if (!isNotifGranted(activity)) {
                    showToastProperly(context)
                }
            },
            leftButton = stringResource(R.string.dialog_notif_permission_no_show),
            onLeftButton = { handleNotShowAgain() }
        )

        // 锁屏方式弹窗
        ModalDialog(
            onDismiss = {
                showLockMethodDialog = false

                // 一次性同步两种锁屏方式的实时状态，避免分步更新误关锁屏开关
                val a11y = isAccessibilityEnabled(context)
                val dpmActive = isDpmActive(context)
                viewModel.syncLockPermissions(a11y, dpmActive)
            },
            title = stringResource(R.string.dialog_lock_method_title),
            description = stringResource(R.string.dialog_lock_method_desc),
            leftButton = stringResource(R.string.dialog_lock_method_accessibility),
            dismissText = stringResource(R.string.action_cancel),
            onLeftButton = {
                showLockMethodDialog = false
                openAccessibilitySettings(context)
            },
            confirmText = stringResource(R.string.dialog_lock_method_admin),
            onConfirm = {
                showLockMethodDialog = false
                requestDeviceAdmin(context)
            },
            show = showLockMethodDialog
        )

        // 小时/分钟输入弹窗：主页显示 0 时，输入框仍为空白
        val field = editingField
        if (field != null) {
            TimeFieldDialog(
                field = field,
                initialValue = when (field) {
                    TimeField.HOUR -> if (hours > 0) hours.toString() else ""
                    TimeField.MINUTE -> if (minutes > 0) minutes.toString() else ""
                },
                onDismiss = { editingField = null },
                onReset = {
                    // 重置：当前编辑的单位清空为默认的空，并关闭弹窗
                    val restSeconds = when (field) {
                        TimeField.HOUR -> minutes * 60
                        TimeField.MINUTE -> hours * 3600
                    }
                    applyTotalSeconds(restSeconds)
                    editingField = null
                },
                onConfirm = { raw ->
                    // 确定：写入输入框的数值，超出 Int 秒上限时按上限截断
                    val parsed = raw.toLongOrNull() ?: Long.MAX_VALUE
                    val total = when (field) {
                        TimeField.HOUR -> {
                            val hourValue = parsed.coerceAtMost(MAX_HOURS)
                            hourValue * 3600L + minutes * 60L
                        }

                        TimeField.MINUTE -> {
                            val minuteValue = parsed.coerceAtMost(MAX_MINUTES)
                            hours * 3600L + minuteValue * 60L
                        }
                    }
                    applyTotalSeconds(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    editingField = null
                }
            )
        }
    }
}

// 单个时间单位的显示行：左侧固定宽度数值框，右侧固定宽度单位文字
// 行宽固定，三行的数字中心与单位起点因此保持对齐
@Composable
private fun TimeValueRow(
    value: String,
    unit: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.width(TIME_ROW_WIDTH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(TIME_SLOT_WIDTH)
                .height(64.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = unit,
            modifier = Modifier.width(TIME_LABEL_WIDTH),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// 时间输入弹窗：一个数字输入框，按钮顺序为 重置 / 取消 / 确定
@Composable
private fun TimeFieldDialog(
    field: TimeField,
    initialValue: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    // 打开弹窗后自动聚焦并弹出数字键盘
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
            ) {
                Text(
                    text = stringResource(
                        if (field == TimeField.HOUR) {
                            R.string.dialog_time_hour_title
                        } else {
                            R.string.dialog_time_minute_title
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 输入框只保留数字字符，不限制长度
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it in '0'..'9' } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.action_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = { onConfirm(input) }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            }
        }
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    return AccessibilityPermission.isAccessibilityEnabled(context)
}

fun openAccessibilitySettings(context: Context) {
    AccessibilityPermission.open(context)
}

fun openNotifSetting(activity: Activity) {
    NotificationPermission.request(activity)
}

fun isNotifGranted(activity: Activity): Boolean {
    return NotificationPermission.isGranted(activity)
}

private fun showToastProperly(context: Context) {
    Toast.makeText(
        context,
        context.getString(R.string.toast_enable_notification),
        Toast.LENGTH_LONG
    ).show()
}
