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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.inod.screenofftimer.R
import com.inod.screenofftimer.core.permission.AccessibilityPermission
import com.inod.screenofftimer.core.permission.NotificationPermission
import com.inod.screenofftimer.data.Prefs
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

// 时间数字所在区域的固定宽度，三行共用，保证数字中心落在同一条竖线上
private val TIME_SLOT_WIDTH = 200.dp

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

    // 输入的小时数单独保存：剩余时长仍包含这部分小时时，小时保持不变，分钟不进位
    val inputHours = settings.inputHours
    val totalSeconds = viewModel.leftSeconds.coerceAtLeast(0)
    val hoursPartSeconds = inputHours.toLong() * 3600L

    val displayHours: Int
    val displayMinutes: Int
    val displaySeconds: Int
    if (totalSeconds.toLong() >= hoursPartSeconds) {
        val restSeconds = totalSeconds.toLong() - hoursPartSeconds
        displayHours = inputHours
        displayMinutes = (restSeconds / 60).toInt()
        displaySeconds = (restSeconds % 60).toInt()
    } else {
        displayHours = totalSeconds / 3600
        displayMinutes = (totalSeconds % 3600) / 60
        displaySeconds = totalSeconds % 60
    }

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

    // 重置键：停止计时、清空时间，并清除输入的小时数
    val handleReset: () -> Unit = {
        if (isRunning) {
            viewModel.stop(totalSeconds)
        }
        Prefs.saveInputHours(context, 0)
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

            // 小时输入框：显示输入的小时数，运行中不可修改
            TimeValueRow(
                value = displayHours.toString(),
                unit = stringResource(R.string.home_hour_unit),
                enabled = !isRunning,
                onClick = { editingField = TimeField.HOUR }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 分钟输入框：超过 59 也不进位，运行中不可修改
            TimeValueRow(
                value = displayMinutes.toString(),
                unit = stringResource(R.string.home_min_unit),
                enabled = !isRunning,
                onClick = { editingField = TimeField.MINUTE }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 秒读数：与上面两行同样的结构，整行按内容居中
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(TIME_SLOT_WIDTH),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displaySeconds.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_second_unit),
                    style = MaterialTheme.typography.bodyLarge,
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

        // 小时/分钟输入弹窗：输入框打开时整体选中已有数字
        val field = editingField
        if (field != null) {
            TimeFieldDialog(
                field = field,
                initialValue = when (field) {
                    TimeField.HOUR -> if (displayHours > 0) displayHours.toString() else ""
                    TimeField.MINUTE -> if (displayMinutes > 0) displayMinutes.toString() else ""
                },
                onDismiss = { editingField = null },
                onConfirm = { raw ->
                    // 确定：空输入按 0 处理，超长数字按上限截断
                    val parsed = if (raw.isEmpty()) 0L else (raw.toLongOrNull() ?: Long.MAX_VALUE)
                    when (field) {
                        TimeField.HOUR -> {
                            val hourValue = parsed.coerceAtMost(MAX_HOURS).toInt()
                            val total = (hourValue.toLong() * 3600L + displayMinutes.toLong() * 60L)
                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt()
                            Prefs.saveInputHours(context, hourValue)
                            applyTotalSeconds(total)
                        }

                        TimeField.MINUTE -> {
                            val minuteValue = parsed.coerceAtMost(MAX_MINUTES).toInt()
                            val total = (displayHours.toLong() * 3600L + minuteValue.toLong() * 60L)
                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt()
                            Prefs.saveInputHours(context, displayHours)
                            applyTotalSeconds(total)
                        }
                    }
                    editingField = null
                }
            )
        }
    }
}

// 单个时间单位的显示行：固定宽度的数值框 + 单位文字
// 整行按内容宽度居中，左右留白相等；秒行单位只有一字，整行略窄，数字会比另外两行右移约 8dp
@Composable
private fun TimeValueRow(
    value: String,
    unit: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// 时间输入弹窗：数字输入框，按钮为 取消 / 确定
// 输入框打开时整体选中已有内容，直接输入即可覆盖
@Composable
private fun TimeFieldDialog(
    field: TimeField,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // 用 TextFieldValue 携带全选区间，首次组合时内容处于选中状态
    var input by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialValue,
                selection = TextRange(0, initialValue.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    // 打开弹窗后自动聚焦，聚焦完成后再确保整段内容处于选中状态
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (input.text.isNotEmpty()) {
            input = input.copy(selection = TextRange(0, input.text.length))
        }
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
                    onValueChange = { value ->
                        val digits = value.text.filter { it in '0'..'9' }
                        input = if (digits == value.text) {
                            value
                        } else {
                            TextFieldValue(
                                text = digits,
                                selection = TextRange(digits.length)
                            )
                        }
                    },
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
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = { onConfirm(input.text) }) {
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
