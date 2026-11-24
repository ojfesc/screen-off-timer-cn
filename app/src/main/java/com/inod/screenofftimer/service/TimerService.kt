package com.inod.screenofftimer.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.inod.screenofftimer.core.notification.Notifications
import com.inod.screenofftimer.core.permission.AccessibilityPermission
import com.inod.screenofftimer.data.Prefs
import com.inod.screenofftimer.utils.MediaUtils
import com.inod.screenofftimer.utils.lockScreenDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class TimerService : Service() {

    companion object {
        // 服务实例是否存活；判断是否真的在计时请以持久化的截止时间为准
        var isServiceRunning = false

        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_ADD_MINUTE = "ACTION_ADD_MINUTE"
        const val ACTION_REDUCE_MINUTE = "ACTION_REDUCE_MINUTE"

        // 前台通知固定使用同一个 id：进入前台与后续每次刷新都指向它
        private const val NOTIFICATION_ID = 1

        // 通知栏加减按钮每次调整的时长（秒）
        private const val STEP_SECONDS = 10 * 60

        // 截止时间已过但仍在该宽限期内时，仍然执行熄屏等收尾动作；
        // 超过宽限期说明是长期残留的旧任务，只清理状态，避免隔很久突然熄屏
        private const val EXPIRY_GRACE_MILLIS = 10 * 60 * 1000L
    }

    private val notification = Notifications()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 当前在跑的倒计时任务；所有入口都先取消旧任务再启动，保证只有一份在刷新通知
    private var tickJob: Job? = null

    // 收尾动作只执行一次：倒计时任务与其它入口可能同时判定到期
    private val finishing = AtomicBoolean(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        // 由 startForegroundService 拉起的服务必须在 5 秒内进入前台，
        // 这里先按当前剩余时间占位，onStartCommand 会立刻按截止时间再刷新一次
        startForegroundCompat(remainingSeconds())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 没有动作说明是界面回到前台做同步，或服务被系统回收后重建
        val command = intent ?: return syncWithDeadline()

        return when (command.action) {
            ACTION_START -> {
                val duration = command.getIntExtra("time", -1)
                if (duration <= 0) {
                    stopTimer()
                } else {
                    startCountdown(duration)
                    START_STICKY
                }
            }

            // 运行中改时长：以新时长重新计时，不能只写截止时间再把服务停掉
            ACTION_UPDATE -> {
                val duration = command.getIntExtra("time", 0)
                if (duration <= 0) {
                    stopTimer()
                } else {
                    startCountdown(duration)
                    START_STICKY
                }
            }

            // 通知栏加时长：改完截止时间必须重新对齐计时，否则通知里的数字会停在旧值
            ACTION_ADD_MINUTE ->
                shiftDeadline(command.getIntExtra("time_update", STEP_SECONDS).toLong())

            ACTION_REDUCE_MINUTE ->
                shiftDeadline(-command.getIntExtra("time_update", STEP_SECONDS).toLong())

            ACTION_STOP -> stopTimer()

            else -> syncWithDeadline()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 以新时长开始 / 重新开始计时：先把截止时间落盘，再启动唯一的倒计时任务
    private fun startCountdown(duration: Int) {
        finishing.set(false)

        Prefs.saveLeftSeconds(applicationContext, duration)
        Prefs.saveEndTime(applicationContext, System.currentTimeMillis() + duration * 1000L)
        Prefs.saveRunning(applicationContext, true)

        // 立刻进入前台并显示本次时长，之后由倒计时任务按整秒刷新
        startForegroundCompat(duration)
        restartTicking()
    }

    // 通知栏加减时长：在原截止时间上平移，再按新的截止时间对齐服务状态
    private fun shiftDeadline(deltaSeconds: Long): Int {
        val endTime = Prefs.getEndTime(applicationContext)

        // 没有正在进行的计时，加减时长没有意义，直接清理前台状态
        if (endTime <= 0L) return stopTimer()

        Prefs.saveEndTime(applicationContext, endTime + deltaSeconds * 1000L)
        return syncWithDeadline()
    }

    // 以持久化的截止时间为唯一依据同步服务状态：
    // 未到期就继续计时，已到期就执行收尾动作，没有截止时间就清理退出。
    // 服务被回收后重建、界面回到前台都走这里，所以“到点熄屏”不再依赖某一次启动的内存协程
    private fun syncWithDeadline(): Int {
        val endTime = Prefs.getEndTime(applicationContext)
        if (endTime <= 0L) return stopTimer()

        if (remainingSeconds() > 0) {
            Prefs.saveRunning(applicationContext, true)
            restartTicking()
            return START_STICKY
        }

        // 已到期：宽限期内仍然执行收尾动作，超过宽限期只清理残留状态
        if (System.currentTimeMillis() - endTime <= EXPIRY_GRACE_MILLIS) {
            tickJob?.cancel()
            tickJob = null
            finishTimer()
        } else {
            stopTimer()
        }
        return START_NOT_STICKY
    }

    // 启动唯一的倒计时任务：先取消上一份，避免两个任务同时刷新同一个通知
    private fun restartTicking() {
        tickJob?.cancel()

        tickJob = scope.launch {
            while (isActive) {
                if (finishing.get()) break

                val remainingMillis =
                    Prefs.getEndTime(applicationContext) - System.currentTimeMillis()
                if (remainingMillis <= 0L) break

                val remaining = ceilSeconds(remainingMillis)

                // 倒计时用 startForeground 刷新前台通知：这条通道不依赖 POST_NOTIFICATIONS，
                // 而 NotificationManager.notify 在没有通知权限时会被丢弃，通知里的数字就会停住
                startForegroundCompat(remaining)

                Prefs.saveLeftSeconds(applicationContext, remaining)
                sendUpdate(remaining)

                // 睡到下一个整秒，避免“刷新耗时 + 固定 1 秒”造成的累计漂移与跳秒
                delay(nextTickDelayMillis(remainingMillis))
            }

            finishTimer()
        }
    }

    // 倒计时到点：先清掉截止时间与运行标记（保证只收尾一次），再执行熄屏等动作
    private fun finishTimer() {
        if (!finishing.compareAndSet(false, true)) return

        Prefs.saveEndTime(applicationContext, 0L)
        Prefs.saveRunning(applicationContext, false)

        // 把可点的时长恢复成本次运行的时长，不足一分钟也不会被分钟取整吃掉
        val restoreSeconds = Prefs.getStartedSeconds(applicationContext)
            .takeIf { it > 0 } ?: Prefs.getLastDrag(applicationContext)

        Prefs.saveLeftSeconds(applicationContext, restoreSeconds)
        sendUpdate(restoreSeconds)

        scope.launch { performFinishActions() }
    }

    // 用户手动停止，或没有有效计时任务：清掉截止时间与前台通知后退出
    private fun stopTimer(): Int {
        // 主动停止时不再执行到点的收尾动作
        finishing.set(true)
        tickJob?.cancel()
        tickJob = null

        Prefs.saveEndTime(applicationContext, 0L)
        Prefs.saveRunning(applicationContext, false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        return START_NOT_STICKY
    }

    // 到点后的收尾动作：按设置依次回桌面、暂停媒体、打开应用，最后熄屏
    private suspend fun performFinishActions() {
        if (Prefs.isGoHome(applicationContext)) {
            goHome(applicationContext)
            delay(500.milliseconds)
        }

        if (Prefs.isStopMedia(applicationContext)) {
            stopMedia(applicationContext)
            delay(500.milliseconds)
        }

        if (Prefs.isOpenApp(applicationContext)) {
            openApp(applicationContext)
            delay(500.milliseconds)
        }

        if (Prefs.isLockScreen(applicationContext)) {
            lockScreen(applicationContext)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // 当前剩余秒数：向上取整，整点显示完整时长，最后不足 1 秒也不会提前显示 0
    private fun remainingSeconds(): Int {
        val remainingMillis =
            Prefs.getEndTime(applicationContext) - System.currentTimeMillis()
        return if (remainingMillis <= 0L) 0 else ceilSeconds(remainingMillis)
    }

    // 毫秒向上取整到秒，并限制在 Int 范围内
    private fun ceilSeconds(millis: Long): Int =
        ((millis + 999L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    // 距下一个整秒还有多少毫秒；正好落在整秒上时睡满 1 秒
    private fun nextTickDelayMillis(remainingMillis: Long): Long {
        val rest = remainingMillis % 1000L
        return if (rest == 0L) 1000L else rest
    }

    // 把前台通知刷新成最新的剩余时间
    private fun startForegroundCompat(remaining: Int) {
        try {
            val notif = notification.defaultNotification(this, remaining)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.w("TIMER_SVC", "startForegroundCompat failed: ${e.message}")
        }
    }

    // 把剩余秒数发给界面，界面据此刷新计数
    private fun sendUpdate(seconds: Int) {
        val intent = Intent("TIMER_TICK").apply {
            setPackage(packageName)
            putExtra("time_left", seconds)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        tickJob?.cancel()
        tickJob = null
        // 不在这里清 running 标记与截止时间：服务被系统回收后重建时，
        // syncWithDeadline 还要靠它们把计时接上
        scope.cancel()
        super.onDestroy()
    }

    // 任务被划掉时如果已经过了截止时间，立刻按计划收尾，避免残留前台通知
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (remainingSeconds() <= 0) syncWithDeadline()
    }

    private fun lockScreen(context: Context) {
        lockScreenDevice(context)
    }

    private fun stopMedia(context: Context) {
        MediaUtils().stopMedia(context)
    }

    private fun goHome(context: Context) {
        MediaUtils().goToHome(context)
    }

    private fun openApp(context: Context) {
        val a11y = MediaControlAccessibilityService.instance
        if (a11y != null) {
            // 无障碍已开启：用可信的 Context 直接打开应用，可绕过后台启动限制
            a11y.openApp(applicationContext)
        } else {
            // 无障碍未开启：跳到无障碍设置页，让用户开启该功能的前提条件
            AccessibilityPermission.open(applicationContext)
        }
    }
}
