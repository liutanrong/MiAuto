package com.andrerinas.openheadunit.icoca

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.launch

/**
 * Full-screen host for the ICCOA CarLink phone stream.
 *
 * The ICCOA SDK does not decode video itself — it hands the H.264 stream to a Surface you supply
 * via `UCarAdapter.startCast(surface, w, h)`. This activity exists purely to own that surface and
 * keep it alive while the phone is projecting, which is why it stays deliberately thin: one
 * [SurfaceView], a status hint, and a way out.
 *
 * It deliberately does *not* reuse `AapProjectionActivity`. That activity is wired end-to-end into
 * the Android Auto session (video-focus negotiation, `CommManager` transport state, AAP keyframes)
 * and MiAuto never opens an AA session, so every one of its branches would be dead code guarded by
 * a status that can never occur.
 */
class IcocaProjectionActivity : AppCompatActivity() {

    /** onCreate 里赋值，onResume 里要用，所以提到类级别。 */
    private lateinit var bridge: IcocaBridge

    // ★ Wi-Fi 高性能锁。P2P 组在没有流量时会让射频进省电模式，唤醒间隔约 100ms，
    //   表现就是「每隔一会儿卡一下」的规律性抖动。投屏期间持锁可显著减少这种抖动。
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 必须锁横屏。SDK 是按 landscape 画布协商的（setScreenSize 传的就是
        //   max/min），如果这里跟随传感器，手机竖着拿时 Surface 会变成 1260x2800，
        //   与协商尺寸相反 —— 日志里就是 "surfaceChanged 1260x2800" 配
        //   "startCast(2800x1260)"，方向对不上，媒体协商直接失败。
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // A projection session can run for a long time; never let the panel sleep mid-drive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWifiLocks()
        setContentView(R.layout.activity_icoca_projection)
        hideSystemUi()

        // 把构建号印在等待页角落：现场判断「装的是不是新包」不必再翻日志。
        findViewById<android.widget.TextView>(R.id.icoca_build)?.text =
            com.miauto.app.IccoaManager.BUILD_TAG

        bridge = App.provide(this).icocaBridge
        val surfaceView = findViewById<SurfaceView>(R.id.icoca_surface)
        // ★ 触控回传（UIBC）。SDK 有 sendTouchEvent()，但上游从未调用 —— 车机上点什么都
        //   不会有反应，看起来就像「画面冻住了」。这里把每一次触摸按 View 实际尺寸归一化后
        //   交给 SDK，由它映射到手机屏幕坐标。
        surfaceView.setOnTouchListener { view, event ->
            bridge.sendTouch(event, view.width, view.height)
            true
        }
        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceCreated")
                    // 此刻宽高还没确定（实测 surfaceChanged 要晚约 2 ms 才到），
                    // 传 0 让 IccoaManager 等到真实尺寸再启动投屏。
                    bridge.setSurface(holder.surface, 0, 0)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.i("ICCOA projection: surfaceChanged ${width}x$height")
                    // 尺寸变化会让 SDK 底下的 Surface 失效，必须用新尺寸重新 startCast。
                    bridge.setSurface(holder.surface, width, height)
                    startPixelProbe()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceDestroyed")
                    // 倒车影像、系统弹窗等会直接把 Surface 收走。这里不清状态的话，
                    // 回来时若 Surface 对象恰好复用，去重判断会认为「没变化」而跳过重投。
                    stopPixelProbe()
                    bridge.onSurfaceDestroyed()
                }
            }
        )

        findViewById<View>(R.id.icoca_disconnect).setOnClickListener {
            AppLog.i("ICCOA projection: disconnect tapped")
            // Drop the session but keep advertising: the standby screen goes straight back to
            // "waiting for phone" and a fresh pairing code, instead of a dead link that needs an
            // app restart to recover.
            bridge.disconnectSession()
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.state.collect { state ->
                    val hint = findViewById<android.widget.TextView>(R.id.icoca_hint) ?: return@collect
                    val veil = findViewById<View>(R.id.icoca_veil) ?: return@collect
                    // ★ 判据是「画面是否真的出来」，不是 phase。
                    //   phase 会在每次会话重建时被 SDK 的 CONNECTING 改回 Pairing，而
                    //   startCast 不会每次都重发，于是 onCastStarted 也不会再触发 ——
                    //   旧写法（phase == Connected 才隐藏）就会把提示永久留在画面上，
                    //   也就是"已经连上了还显示正在连接"。
                    // 每次连接进度变化都续一次采样窗：手机连得慢时不至于把预算耗光。
                    if (!state.castReady && state.progress != probeProgress) {
                        probeProgress = state.progress
                        if (!probeVisible) startPixelProbe()
                    }

                    if (state.castReady) {
                        stopPixelProbe()
                        // 首帧确认后把当前日夜模式同步给手机（只同步一次，避免每帧都发）。
                        if (!dayNightSynced) {
                            dayNightSynced = true
                            bridge.sendDayNight(isNightMode())
                        }
                        // 首帧到了 → 遮罩淡出。180ms 的淡出比直接 GONE 更稳：
                        // 解码第一帧与实际送显之间有一两帧的差，硬切会闪一下黑。
                        if (veil.visibility == View.VISIBLE) {
                            veil.animate().alpha(0f).setDuration(180L).withEndAction {
                                veil.visibility = View.GONE
                            }.start()
                        }
                    } else {
                        // 会话重建（如断开重连）会让 castReady 回到 false，此时重新开始采样，
                        // 否则遮罩只能等下一次 surfaceChanged 才可能被撤掉。
                        if (veil.visibility == View.GONE) startPixelProbe()
                        veil.animate().cancel()
                        veil.alpha = 1f
                        veil.visibility = View.VISIBLE
                        hint.text = when {
                            state.recovering -> "画面初始化中，正在重新建立连接…"
                            state.progress > 0 -> "正在连接手机… ${state.progress}%"
                            else -> getString(R.string.da_connecting)
                        }
                    }
                }
            }
        }
    }

    // ★ 方控：把面板/方向盘按键透传给手机。
    //   返回 true 表示已由 ICCOA 处理，不再交给本地控件；返回 false 说明这个键
    //   没有对应语义（比如音量）或者当前没在投屏，继续走原生分发。
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (bridge.handleKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // 车机切换深色/浅色时同步给手机，手机端界面跟着变，夜间不刺眼。
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (dayNightSynced) {
            bridge.sendDayNight(isNightMode())
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    // ==================================================================
    // 像素采样：直接问「屏幕上到底有没有画面」
    // ==================================================================
    //
    // 为什么不用 SDK 的 onSurfaceReady()：反编译 VideoPlayerV2$1.onOutputBufferAvailable，
    // 它的触发条件是
    //     mNeedRender && !mIsSurfaceAlReady
    //     && mLastReadyCountTime != -1
    //     && mLastReadyCountTime >= presentationTimeUs
    // 而 mLastReadyCountTime 只在输入侧「第一帧 且 mLastShownTime == 0」时才被赋值。
    // 任一条件不满足，画面已经在放、回调却永远不来 —— 上层就会认定黑屏，进而白白
    // 断掉一个本来正常的会话。像素采样是唯一不依赖 SDK 内部状态的判据。
    private val probeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var probeVisible = false
    /** 采样窗口的截止时刻（elapsedRealtime）。窗口用「时间」而不是「次数」来限。 */
    private var probeDeadline = 0L
    /** 上一次触发采样时的进度，用来在每次进度变化时续命。 */
    private var probeProgress = -1
    /** 是否已经把日夜模式同步给手机（首帧之后做一次即可）。 */
    private var dayNightSynced = false

    private val probeRunnable = object : Runnable {
        override fun run() {
            val sv = findViewById<SurfaceView>(R.id.icoca_surface) ?: return
            if (probeVisible) return
            // ★ 以前这里是「采满 40 次就停」，而 40 次只有 10 秒 —— 手机连得慢一点，
            //   采样预算在画面出现之前就用完，遮罩再也撤不掉，看起来就是彻底黑屏。
            //   现在改成时间窗，并且每收到一次进度回调就续命，等多久就采多久。
            if (android.os.SystemClock.elapsedRealtime() > probeDeadline) return
            sampleOnce(sv)
            probeHandler.postDelayed(this, PROBE_INTERVAL_MS)
        }
    }

    private fun startPixelProbe() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        probeVisible = false
        probeDeadline = android.os.SystemClock.elapsedRealtime() + PROBE_WINDOW_MS
        probeHandler.removeCallbacks(probeRunnable)
        // 首个 buffer 不可能瞬间出来，先等一拍再采，省掉几次必然全黑的拷贝。
        probeHandler.postDelayed(probeRunnable, 400L)
    }

    private fun stopPixelProbe() {
        probeHandler.removeCallbacks(probeRunnable)
    }

    private fun sampleOnce(sv: SurfaceView) {
        if (!sv.holder.surface.isValid) return
        // 采 64x36 就够：全黑判定不需要细节，小图能把 PixelCopy 的开销压到微秒级。
        val bmp = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(
                sv, bmp,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        val lit = countLitPixels(bmp)
                        if (lit > 0) {
                            probeVisible = true
                            stopPixelProbe()
                            AppLog.i("ICCOA projection: 像素采样确认画面已出（非黑像素 $lit 个）")
                            bridge.onPixelsVisible()
                        }
                    }
                    bmp.recycle()
                },
                probeHandler
            )
        } catch (t: Throwable) {
            bmp.recycle()
            AppLog.i("ICCOA projection: PixelCopy 失败 $t")
        }
    }

    /**
     * 数「明显不是黑」的像素。
     *
     * 纯黑屏解码器输出的是 (0,0,0)，而真实画面（哪怕深色主题的手机桌面）总有
     * 状态栏、图标、文字带来非零分量。阈值取 24 是为了躲开 H.264 量化噪声：
     * 实测黑屏时偶尔会有 RGB 值 1~8 的抖动，那不是画面。
     */
    private fun countLitPixels(bmp: android.graphics.Bitmap): Int {
        var lit = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 24 || g > 24 || b > 24) lit++
            }
        }
        return lit
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    // ★ 倒车影像 / 系统浮层会把本页切到后台，但**不会**销毁 Surface：buffer 失效了，
    //   回调却一次都不来。回到前台时必须主动重建一次投屏会话，否则画面停在最后一帧。
    //   只有离开超过 800ms 才重建，避免下拉通知栏之类的短暂失焦也触发。
    private var pausedAt = 0L

    override fun onPause() {
        pausedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val away = if (pausedAt > 0L) SystemClock.elapsedRealtime() - pausedAt else 0L
        if (away > 800L) {
            AppLog.i("ICCOA projection: resumed after ${away}ms — rebind")
            bridge.rebindAfterResume()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    /**
     * 两把锁分开处理。
     *
     * 原先写在同一个 try 里，MulticastLock 因为缺 CHANGE_WIFI_MULTICAST_STATE 抛了
     * SecurityException，结果连已经拿到的 WifiLock 都被这行 catch 吞掉了，日志里只剩
     * 「Wi-Fi 锁获取失败」，看不出到底是哪一把没拿到。现在各拿各的，失败也能分清。
     */
    private fun acquireWifiLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (wifiLock == null) {
            try {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "icoca:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                AppLog.i("ICCOA: WifiLock(HIGH_PERF) 已获取")
            } catch (t: Throwable) {
                AppLog.i("ICCOA: WifiLock 获取失败 $t")
            }
        }
        if (multicastLock == null) {
            try {
                multicastLock = wm.createMulticastLock("icoca:mcast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                AppLog.i("ICCOA: MulticastLock 已获取")
            } catch (t: Throwable) {
                AppLog.i("ICCOA: MulticastLock 获取失败 $t")
            }
        }
    }

    private fun releaseWifiLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
        multicastLock = null
    }

    override fun onDestroy() {
        releaseWifiLocks()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    companion object {
        /** 像素采样周期。250ms 足够快，又不会干扰渲染线程。 */
        private const val PROBE_INTERVAL_MS = 250L
        /**
         * 采样时间窗。每次收到「连接进度变化」都会重新计时，所以它实际表示
         * 「最后一次进度变化之后还愿意等多久」。
         */
        private const val PROBE_WINDOW_MS = 60_000L

        /** Read by MainActivity to avoid stacking a second projection instance. */
        @Volatile
        var isForeground: Boolean = false
            private set

        fun intent(context: Context): Intent = Intent(context, IcocaProjectionActivity::class.java)
    }
}
