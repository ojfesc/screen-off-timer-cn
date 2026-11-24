package com.inod.screenofftimer.core.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.inod.screenofftimer.data.Prefs
import com.inod.screenofftimer.service.TimerService

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RECEIVER", "onReceive: ${intent.action}")

        when (intent.action) {
            // 通知栏加减按钮：交给服务按新的截止时间重新对齐计时，
            // 服务不在运行时也能被拉起并按截止时间恢复
            "ACTION_ADD_MINUTE" -> startTimerService(
                context, TimerService.ACTION_ADD_MINUTE, intent
            )

            "ACTION_REDUCE_MINUTE" -> startTimerService(
                context, TimerService.ACTION_REDUCE_MINUTE, intent
            )

            "NOTIF_STOP" -> {
                Prefs.saveRunning(context, false)
                // 通过服务清除截止时间，否则停止后重新进入界面又会被当成未完成任务恢复
                startTimerService(context, TimerService.ACTION_STOP, intent)
            }
        }
    }

    // 统一用 startForegroundService 拉起服务：服务需要处于前台才能持续刷新倒计时，
    // 在后台直接 startService 在 Android 8+ 会抛异常
    private fun startTimerService(context: Context, serviceAction: String, source: Intent) {
        val intent = Intent(context, TimerService::class.java).apply {
            action = serviceAction

            // 把调用方传入的调整步长带上，未传时由服务使用默认步长
            val step = source.getIntExtra("time_update", 0)
            if (step > 0) putExtra("time_update", step)
        }

        ContextCompat.startForegroundService(context, intent)
    }
}
