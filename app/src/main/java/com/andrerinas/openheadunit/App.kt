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

        // Root support（0.3.20：移到解锁判断之后）。
        //
        // ★ 这里原来是全局调用，而 `component` 是 lazy，第一次访问就会构造 AppComponent，
        //   里面 new Settings(app) + 读 debugForceMemoryProfile → 直接碰 SharedPreferences。
        //   开机自启（BOOT_COMPLETED）时用户还没解锁，凭据加密存储不可用 →
        //   IllegalStateException → "Unable to create application" → 进程秒崩。
        //   10/27 18:35 那两次 FATAL EXCEPTION 就是这个（PID 2937 / 2227，间隔 1.8 秒，
        //   正好是系统连着重启了两次）。栈已在 0.3.19 的报告里拿到实锤。

        if (isUserUnlocked()) {
            component.suExecutor.register()

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

        // 在 AppLog.init 之后：历史崩溃摘要要能进 DiagnosticJournal，随报告一起导出。
        reportPreviousCrash()

        // ★ libusbio 加载预检（0.3.5 新增）。
        //
        //   实测出现过 dlopen failed: libusbio.so has bad ELF magic: 00000000 ——
        //   安装包在设备存储层损坏（/data/app 里的 so 读出全零），SDK 首次触到 USB
        //   通道时在主线程抛 UnsatisfiedLinkError 直接崩（21:46 起连崩 9 次）。
        //   这里抢先加载：失败时立刻落一条明确日志（含修复指引），并给出文件头
        //   快照佐证是存储损坏而非版本问题。
        runCatching {
            System.loadLibrary("usbio")
            AppLog.i("[USB] libusbio 预检：加载成功")
        }.onFailure { t ->
            AppLog.e("[USB] libusbio 加载失败 → USB 通道不可用：$t")
            runCatching {
                val f = java.io.File(applicationInfo.nativeLibraryDir, "libusbio.so")
                if (f.isFile) {
                    val head = f.inputStream().use { s ->
                        val b = ByteArray(4); s.read(b); b.joinToString(" ") { "%02x".format(it) }
                    }
                    AppLog.e("[USB] libusbio.so 文件头=$head size=${f.length()}（合法 ELF 应为 7f 45 4c 46；全零=存储损坏，请重装 APK）")
                }
            }
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
     *
     * 0.3.4 起：
     * - 每次崩溃写一份带时间戳的 crash-<ts>.txt（保留最近 5 份，不再互相覆盖），
     *   头部带版本号/板子/线程，方便连着几次崩溃一起看；
     * - 启动时 [reportPreviousCrash] 把历史崩溃摘要注入 AppLog，
     *   于是诊断报告的 journal 段会带上「上次崩过」——用户不再需要
     *   记住崩溃发生在哪一刻，报告自己会说。
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
                val ts = System.currentTimeMillis()
                val ver = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "?"
                val text = "time=$ts\n" +
                    "version=$ver\n" +
                    "android=${android.os.Build.VERSION.RELEASE}(API ${android.os.Build.VERSION.SDK_INT})\n" +
                    "board=${android.os.Build.BOARD} model=${android.os.Build.MODEL}\n" +
                    "thread=${thread.name}\n" +
                    android.util.Log.getStackTraceString(error)
                java.io.File(dir, "crash-$ts.txt").writeText(text)
                java.io.File(dir, "crash-last.txt").writeText(text)
                // 只留最近 5 份：崩溃刷屏时不要把用户存储撑爆。
                val crashes = dir.listFiles { f ->
                    f.name.startsWith("crash-") && f.name.endsWith(".txt")
                } ?: emptyArray()
                if (crashes.size > 5) {
                    crashes.sortedBy { it.name }.take(crashes.size - 5).forEach { it.delete() }
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 启动时把磁盘上的历史崩溃摘要写进 AppLog。
     *
     * <p>必须在 AppLog.init 之后调用：DiagnosticJournal 要处于已初始化状态，
     * 这些行才会进诊断报告。AppLog 未初始化时调用是安全的（退回 logcat），
     * 但那样报告里就看不到。
     */
    private fun reportPreviousCrash() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val crashes = dir.listFiles { f ->
                f.name.startsWith("crash-") && f.name.endsWith(".txt")
            } ?: return
            crashes.sortedBy { it.name }.takeLast(3).forEach { f ->
                val head = f.readText().lineSequence().take(4).joinToString(" | ")
                AppLog.w("[崩溃记录] ${f.name} → $head")
            }
            if (crashes.isNotEmpty()) {
                AppLog.w("[崩溃记录] 共 ${crashes.size} 份崩溃文件在 ${dir.absolutePath}，完整栈见对应文件")
            }
        } catch (_: Throwable) {
        }
    }
}
