package com.andrerinas.openheadunit

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import java.io.File

class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()



        // Enable vector drawable support on older Android versions
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        // Root support
        component.suExecutor.register()

        if (isUserUnlocked()) {
            val settings = Settings(this) // Create a Settings instance
            AppLog.init(settings, this) // Initialize AppLog with settings for conditional logging

            // Sync auto-start settings to device-protected storage so that
            // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
            // can read them during locked boot (before user unlock)
            Settings.syncAutoStartOnBootToDeviceStorage(this, settings.autoStartOnBoot)
            Settings.syncAutoStartOnUsbToDeviceStorage(this, settings.autoStartOnUsb)
            Settings.syncAutoStartOnWifiToDeviceStorage(this, settings.autoStartOnWifi)
            Settings.syncAutoStartWifiSsidToDeviceStorage(this, settings.autoStartWifiSsid)
            Settings.syncAutoStartBtMacsToDeviceStorage(this, settings.autoStartBluetoothDeviceMacs)

            // Apply app theme (runs the live manager when dynamic, or when a saved place
            // can force the app theme even over a static base).
            AppThemeManager.reapply(this, settings)
        } else {
            AppLog.init(null, this) // Initialize with default logging if locked
            AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
        }

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Shown once after boot to open the app"
            bootChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(bootChannel)
        }

        // Register the main broadcast receiver safely for Android 14+ using ContextCompat
        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        @Volatile
        var instance: App? = null
            private set


        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }

    /**
     * 崩溃兜底：把未捕获异常同步写进 logcat 与文件。
     *
     * 这里刻意不用 AppLog（它走队列 + 后台线程，进程被杀时来不及落盘），
     * 直接 android.util.Log.e + 立即 writeText。
     * 下一份诊断报告读的是 logcat，所以这一行 OPENHU FATAL 会跟着进去，
     * 不用再指望用户去接 adb。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                android.util.Log.e("OPENHU", "FATAL-UNCAUGHT thread=" + thread.name, error)
            } catch (_: Throwable) {
            }
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                java.io.File(dir, "crash-last.txt").writeText(
                    "time=" + java.lang.System.currentTimeMillis() +
                        " thread=" + thread.name + "\n" +
                        android.util.Log.getStackTraceString(error)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
