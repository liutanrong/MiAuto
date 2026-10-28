package com.andrerinas.openheadunit

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.andrerinas.openheadunit.connection.carkey.CarKeysManager
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.SUExecutor
import com.andrerinas.openheadunit.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    // 0.3.20：读不到设置就按默认值走，绝不因为「用户还没解锁」把整个 Application 干掉。
    // 0.3.18 在 10/27 18:35 连崩两次，栈头就是这行读 SharedPreferences 抛的
    // IllegalStateException: ...not available until after user is unlocked。
    val videoDecoder = VideoDecoder(
        settings,
        DeviceMemoryProfile.readWithOverride(
            app,
            runCatching { settings.debugForceMemoryProfile }.getOrNull()
        )
    )
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    /**
     * MiAuto's ICCOA CarLink (Xiaomi CarWith) link. Process-scoped: the SDK must be initialised
     * once per process, so this bridge outlives any single activity and is the single owner of
     * the [com.miauto.app.IccoaManager] instance.
     */
    val icocaBridge = IcocaBridge(app)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()
}
