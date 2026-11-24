package com.inod.screenofftimer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inod.screenofftimer.data.Prefs
import com.inod.screenofftimer.service.TimerService
import com.inod.screenofftimer.ui.ThemeMode
import com.inod.screenofftimer.viewmodel.TimerViewModel

class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.black)

        enableEdgeToEdge()

        setContent {
            val viewModel: TimerViewModel = viewModel()
            val settings by viewModel.allSettings.collectAsState()
            val isDark = when (settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // update system reactively
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            App()
        }
    }

    override fun onResume() {
        super.onResume()
        checkServiceAnomalies()
    }

    // 只清理“标记为运行、但没有截止时间、服务也不在本进程”的残留状态。
    // 不能像以前那样只看 TimerService.isServiceRunning：进程被系统回收后该标志一定是 false，
    // 会把仍在计时的任务误判成异常任务停掉，表现为通知倒计时停住、到点也不再熄屏
    private fun checkServiceAnomalies() {
        if (TimerService.isServiceRunning) return
        if (Prefs.getEndTime(this) > 0L) return
        if (!Prefs.isRunning(this)) return

        Prefs.saveRunning(this, false)

        val stopIntent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
        }
        startService(stopIntent)
    }
}
