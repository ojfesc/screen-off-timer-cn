package com.inod.screenofftimer.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.inod.screenofftimer.data.Prefs
import com.inod.screenofftimer.service.TimerService
import com.inod.screenofftimer.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimerViewModel(
    application: Application,
    private val state: SavedStateHandle
) : AndroidViewModel(application) {
    private val context = getApplication<Application>()

    val allSettings = Prefs.settingsFlow(context)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = Prefs.AllSettings()
        )

    var theme by mutableStateOf(state.get<ThemeMode>("theme") ?: ThemeMode.SYSTEM)
        private set
    var isRunning by mutableStateOf(state.get<Boolean>("is_running") ?: false)
        private set
    var leftSeconds by mutableIntStateOf(state.get<Int>("left_seconds") ?: 0)
        private set
    var startedSeconds by mutableIntStateOf(state.get<Int>("started_seconds") ?: 0)
        private set
    var minutes by mutableIntStateOf(state.get<Int>("minutes") ?: 0)
        private set
    var accessibility by mutableStateOf(state.get<Boolean>("accessibility") ?: false)
        private set
    var deviceAdmin by mutableStateOf(state.get<Boolean>("device_admin") ?: false)
        private set
    var isLockScreen by mutableStateOf(state.get<Boolean>("is_lock_screen") ?: false)
        private set
    var isStopMedia by mutableStateOf(state.get<Boolean>("is_stop_media") ?: false)
        private set
    var isGoHome by mutableStateOf(state.get<Boolean>("is_go_home") ?: false)
        private set
    var isAutoCloseOnStart by mutableStateOf(state.get<Boolean>("is_auto_close_on_start") ?: false)
    var isOpenApp by mutableStateOf(state.get<Boolean>("open_app") ?: false)
        private set
    var isDynamicColor by mutableStateOf(state.get<Boolean>("is_dynamic_color") ?: false)
        private set
    var isNotifPermission by mutableStateOf(state.get<Boolean>("is_notif_permission") ?: false)
        private set
    var isNoShowNotifPermission by mutableStateOf(state.get<Boolean>("is_no_show_notif") ?: false)
        private set

    init {
        viewModelScope.launch {
            allSettings.collect { settings ->
                isRunning = settings.isRunning
                if (!isRunning) {
                    leftSeconds = settings.leftSeconds
                }
            }
        }
    }

    // flow for sync data
    val getRunning = Prefs.runningFlow(context)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            isRunning
        )

    val getLeftSeconds = Prefs.leftSecondsFlow(context)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            leftSeconds
        )

    init {
        loadInitialSettings()
    }

    // 界面回到前台时与服务对齐：没有计时刻表就不启动服务，避免每次进入界面都闪一下前台通知
    fun reconcileTimerState() {
        if (Prefs.getEndTime(context) <= 0L && !Prefs.isRunning(context)) return

        val intent = Intent(application, TimerService::class.java)
        application.startService(intent)
    }

    init {
        viewModelScope.launch {
            getLeftSeconds.collect { seconds ->
                if (isRunning) {
                    leftSeconds = seconds
                    state["left_seconds"] = seconds
                }
            }
        }
    }

    private fun loadInitialSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = Prefs.getAllSettings(context)

            withContext(Dispatchers.Main) {
                theme = state.get<ThemeMode>("theme") ?: settings.theme
                isRunning = state.get<Boolean>("is_running") ?: settings.isRunning
                leftSeconds = state.get<Int>("left_seconds") ?: settings.leftSeconds
                startedSeconds = state.get<Int>("started_seconds") ?: settings.startedSeconds
                minutes = settings.minutes
                accessibility = settings.accessibility
                // 冷启动时恢复设备管理员状态，避免首帧 deviceAdmin 恒为 false
                deviceAdmin = state.get<Boolean>("device_admin") ?: settings.deviceAdmin
                isLockScreen = settings.isLockScreen
                isStopMedia = settings.isStopMedia
                isGoHome = settings.isGoHome
                isAutoCloseOnStart = settings.isAutoCloseOnStart
                isOpenApp = settings.openApp
                isDynamicColor = settings.isDynamicColor
                isNotifPermission = settings.isNotifPermission
                isNoShowNotifPermission = settings.isNoShowNotif

                // 有运行标记，或还有未完成的截止时间，都要让服务按截止时间接上：
                // 进程被系统回收后剩余时间只能靠截止时间续算，只看运行标记会漏掉已到期的这次熄屏
                if (isRunning || Prefs.getEndTime(context) > 0L) {
                    reconcileTimerState()
                }
            }
        }
    }

    //function
    fun updateTheme(value: ThemeMode) {
        theme = value
        state["theme"] = value
        Prefs.saveTheme(context, value)
    }

    // 只在状态真的变化时写盘，避免每秒的计时回调反复触发界面与偏好监听的连锁刷新
    fun updateRunning(value: Boolean) {
        if (isRunning != value) isRunning = value
        state["is_running"] = value
        if (Prefs.isRunning(context) != value) {
            Prefs.saveRunning(context, value)
        }
    }

    fun updateLeftSeconds(value: Int) {
        if (leftSeconds == value && Prefs.getLeftSeconds(context) == value) return

        leftSeconds = value
        state["left_seconds"] = value
        Prefs.saveLeftSeconds(context, value)
    }

    fun updateStartedSeconds(value: Int) {
        startedSeconds = value
        state["started_seconds"] = value
        Prefs.saveStartedSeconds(context, value)
    }

    fun updateMinutes(value: Int) {
        minutes = value
        state["minutes"] = value
        Prefs.saveMinutes(context, value)
    }

    fun updateAccessibility(value: Boolean) {
        accessibility = value
        state["accessibility"] = value
        Prefs.saveAccessibility(context, value)
        applyLockScreenGuard()
    }

    fun updateDeviceAdmin(value: Boolean) {
        deviceAdmin = value
        state["device_admin"] = value
        Prefs.saveDeviceAdmin(context, value)
        applyLockScreenGuard()
    }

    // 一次性写入无障碍与设备管理员两个字段，全部同步后再校验锁屏开关，
    // 避免逐个字段更新时出现"一个为 false、另一个尚未写入"的中间态而误关锁屏
    fun syncLockPermissions(accessibilityEnabled: Boolean, deviceAdminActive: Boolean) {
        if (accessibility != accessibilityEnabled) {
            accessibility = accessibilityEnabled
            state["accessibility"] = accessibilityEnabled
            Prefs.saveAccessibility(context, accessibilityEnabled)
        }
        if (deviceAdmin != deviceAdminActive) {
            deviceAdmin = deviceAdminActive
            state["device_admin"] = deviceAdminActive
            Prefs.saveDeviceAdmin(context, deviceAdminActive)
        }
        applyLockScreenGuard()
    }

    // 只有在无障碍与设备管理员都不可用时才关闭锁屏开关，其余情况保留用户选择
    private fun applyLockScreenGuard() {
        if (isLockScreen && !accessibility && !deviceAdmin) {
            isLockScreen = false
            state["is_lock_screen"] = false
            Prefs.saveLockScreen(context, false)
        }
    }

    fun updateLockScreen(value: Boolean) {
        isLockScreen = value
        state["is_lock_screen"] = value
        Prefs.saveLockScreen(context, value)
    }

    fun updateStopMedia(value: Boolean) {
        isStopMedia = value
        state["is_stop_media"] = value
        Prefs.saveStopMedia(context, value)
    }

    fun updateGoHome(value: Boolean) {
        isGoHome = value
        state["is_go_home"] = value
        Prefs.saveGoHome(context, value)
    }

    fun updateAutoCloseOnStart(value: Boolean) {
        isAutoCloseOnStart = value
        state["is_auto_close_on_start"] = value
        Prefs.saveAutoCloseOnStart(context, value)
    }

    fun updateOpenApp(value: Boolean) {
        isOpenApp = value
        state["open_app"] = value
        Prefs.saveOpenApp(context, value)
    }

    fun updateDynamicColor(value: Boolean) {
        isDynamicColor = value
        state["is_dynamic_color"] = value
        Prefs.saveDynamicColor(context, value)
    }

    fun updateNotifPermission(value: Boolean) {
        isNotifPermission = value
        state["is_notif_permission"] = value
        Prefs.saveNotifPermission(context, value)
    }

    fun updateNoShowNotifPermission(value: Boolean) {
        isNoShowNotifPermission = value
        state["is_no_show_notif"] = value
        Prefs.saveNoShowNotifPermission(context, value)
    }

    //timer logic and broadcast
    // 计时服务每秒广播剩余秒数；这里不再用本地 isRunning 过滤，
    // 否则本地状态一旦与真实服务不同步，界面就会停在最后一个数字上
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != "TIMER_TICK") return

            val seconds = intent.getIntExtra("time_left", 0)
            updateLeftSeconds(seconds)

            if (seconds <= 0 && isRunning) {
                updateRunning(false)
            }
        }
    }

    private var isRegistered = false

    fun registerReceiver(context: Context) {
        if (isRegistered) return
        val filter = IntentFilter("TIMER_TICK")
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isRegistered = true
    }

    fun unregisterReceiver(context: Context) {
        if (!isRegistered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
            Log.d("TIMER_VM", "safe ignore unregister")
        }
        isRegistered = false
    }

    fun startTimer(totalSeconds: Int) {
        val intent = Intent(application, TimerService::class.java).apply {
            putExtra("time", totalSeconds)
            action = TimerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        updateRunning(true)
        updateStartedSeconds(totalSeconds)
    }

    fun setTimer(min: Int) {
        updateMinutes(min)
        updateLeftSeconds(min * 60)
        updateStartedSeconds(min * 60)
        if (isRunning) {
            updateTimer(application, min)
        }
    }

    fun saveLastDrag(minutes: Int) {
        Prefs.saveLastDrag(getApplication(), minutes * 60)
    }

    // 运行中改时长：服务会以新时长继续计时，本地状态保持运行中，不能把计时标记提前清掉
    fun updateTimer(context: Context, minutes: Int) {
        val duration = minutes * 60
        val intent = Intent(context, TimerService::class.java).apply {
            putExtra("time", duration)
            action = TimerService.ACTION_UPDATE
        }
        updateLeftSeconds(duration)
        updateRunning(true)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(seconds: Int) {
        updateRunning(false)
        stopTimer(application, seconds)
    }

    fun stopTimer(context: Context, seconds: Int) {
        val intent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra("time", seconds)
        }
        context.startService(intent)
    }
}
