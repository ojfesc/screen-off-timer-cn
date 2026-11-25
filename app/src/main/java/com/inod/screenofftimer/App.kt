package com.inod.screenofftimer

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inod.screenofftimer.ui.AppNavGraph
import com.inod.screenofftimer.ui.theme.ScreenOffTimerTheme
import com.inod.screenofftimer.viewmodel.TimerViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun App() {
    val viewModel: TimerViewModel = viewModel()

    val settings by viewModel.allSettings.collectAsState()

    ScreenOffTimerTheme(
        themeMode = settings.theme,
        useDynamicColor = settings.isDynamicColor
    ) {
        // 放在导航图之前：依次检查电池优化豁免与通知权限，缺哪项就用不可关闭的弹窗挡住界面
        BatteryOptimizationGate(LocalContext.current)
        NotificationPermissionGate(LocalContext.current)

        AppNavGraph(
            viewModel = viewModel
        )
    }
}
