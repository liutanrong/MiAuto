package com.andrerinas.openheadunit.icoca

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 必须锁横屏。SDK 是按 landscape 画布协商的（setScreenSize 传的就是
        //   max/min），如果这里跟随传感器，手机竖着拿时 Surface 会变成 1260x2800，
        //   与协商尺寸相反 —— 日志里就是 "surfaceChanged 1260x2800" 配
        //   "startCast(2800x1260)"，方向对不上，媒体协商直接失败。
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // A projection session can run for a long time; never let the panel sleep mid-drive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_icoca_projection)
        hideSystemUi()

        val bridge = App.provide(this).icocaBridge
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
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceDestroyed")
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
                    findViewById<View>(R.id.icoca_hint)?.visibility =
                        if (state.phase == IcocaBridge.Phase.Connected) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
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
        /** Read by MainActivity to avoid stacking a second projection instance. */
        @Volatile
        var isForeground: Boolean = false
            private set

        fun intent(context: Context): Intent = Intent(context, IcocaProjectionActivity::class.java)
    }
}
