package com.andrerinas.openheadunit.connection

import android.content.Context
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the BYD cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 */
class IcocaBridge(context: Context) : IccoaManager.Listener {

    /** Coarse connection phase, mapped from the SDK's numeric state. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /** Phone found us and is pairing / negotiating (PIN exchanged, Wi-Fi or USB negotiated). */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
        /** Last raw SDK log line, kept for the diagnostics screen. */
        val lastLog: String? = null,
        /**
         * Connection progress 0..100, straight from the SDK.
         *
         * The projection activity has to exist before the SDK finishes its RTSP negotiation —
         * it owns the Surface the sink draws into. Waiting for [Phase.Connected] is a deadlock:
         * the SDK stalls at 80% because there is no Surface, so it never reports Connected.
         */
        val progress: Int = 0,
        /**
         * 第一帧已经解码出来（SDK 的 onSurfaceReady）。
         *
         * 投屏页的「正在连接」提示以它为准：`phase` 在会话重建时会被 SDK 的 CONNECTING 改回
         * Pairing，而 startCast 不会每次都重发，于是提示会永久挂在那儿 —— 用户看到的就是
         * "已经连上了还显示正在连接"。
         */
        val castReady: Boolean = false,
        /** 首场会话没出帧，正在自动重建 —— 投屏页据此显示「正在重新建立画面」。 */
        val recovering: Boolean = false,
        /** 手机端正在播放的曲目（"标题 / 歌手"）。没有媒体会话时为 null。 */
        val music: String? = null,
        /** 手机端电话状态（空闲 / 来电 / 通话中）。 */
        val callState: String? = null,
        /** 当前数据通道的可读描述（"USB AOA 有线" / "P2P Wi-Fi 5GHz"）。0.3.7。 */
        val transport: String? = null,
        /** 手机端连接异常丢失（僵死画面防护）。投屏页收到后应退出。0.3.7。 */
        val linkLost: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(context.applicationContext, this)

    @Volatile
    private var started = false

    /** Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising. */
    fun start() {
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        manager.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        _state.update { it.copy(phase = Phase.Idle, pinCode = null) }
    }

    val isStarted: Boolean get() = started

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager.disconnectSession()
        _state.update { it.copy(phase = Phase.Advertising, pinCode = null) }
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Forwards a touch on the car screen back to the phone (UIBC).
     *
     * The SDK maps our display coordinate space onto the phone's own screen, so the values we
     * pass must be expressed in the panel coordinate system the view occupies.
     */
    fun sendTouch(event: android.view.MotionEvent, viewWidth: Int, viewHeight: Int) =
        manager.sendTouch(event, viewWidth, viewHeight)

    /**
     * Hands the projection [surface] to the SDK. Idempotent: the SDK ignores a repeated call for
     * an already-casting surface, and [IccoaManager] only starts the cast once.
     */
    fun setSurface(surface: Surface, width: Int, height: Int) =
        manager.setSurface(surface, width, height)

    fun onSurfaceDestroyed() = manager.onSurfaceDestroyed()

    fun rebindAfterResume() = manager.rebindAfterResume()

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionState(stateCode: Int) {
        // ★ 只处理有明确含义的码，其余保持当前状态不变。
        //
        //   SDK 在 CONNECTED(3) 之后还会继续回调 -1（日志里是 "CONNECTED -> STATE_-1"
        //   紧跟在 progress 100 后面），那是状态机切换的过渡值，不是断开。原实现把所有
        //   未知码一律映射成 Advertising，结果画面已经投出来了，投屏页上还挂着
        //   「正在连接手机」，而且 phase 一变，MainActivity 的打开标志也被重置。
        val phase = when (stateCode) {
            0 -> Phase.Advertising
            1, 2 -> Phase.Pairing
            3 -> Phase.Connected
            else -> null
        }
        AppLog.i("ICCOA: connection state $stateCode -> ${phase ?: "(keeping current)"}")
        if (stateCode in 1..3) {
            // 新会话开始/协商继续：清掉上一轮的丢链标志（投屏页据此不误退）
            _state.update { it.copy(linkLost = false) }
        }
        if (phase != null) updatePhase(phase)
    }

    override fun onLinkLost() {
        AppLog.w("ICCOA: link lost — projection page should exit")
        _state.update { it.copy(linkLost = true) }
    }

    override fun onTransportLabel(label: String?) {
        _state.update { it.copy(transport = label) }
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        _state.update {
            it.copy(phase = Phase.Pairing, pinCode = pinCode, carDeviceName = carDeviceName)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(Phase.Connected)
    }

    override fun onProgress(progress: Int) {
        AppLog.i("ICCOA: progress $progress")
        _state.update {
            when {
                // 新会话开始：先把上一轮的"首帧已出"清掉
                // 首个进度事件可能是 10 也可能是 20；0 是断开，不清 castReady
                progress in 1..20 -> it.copy(progress = progress, castReady = false, phase = Phase.Pairing)
                // 协商走完，即使 SDK 之后再发 -1，也不该退回"正在连接"
                progress >= 100 -> it.copy(progress = progress, phase = Phase.Connected)
                else -> it.copy(progress = progress)
            }
        }
    }

    override fun onFirstFrame() {
        AppLog.i("ICCOA: first frame rendered")
        _state.update { it.copy(castReady = true, recovering = false, phase = Phase.Connected) }
    }

    /**
     * 像素采样确认画面已经在屏幕上。
     *
     * 与 [onFirstFrame] 的区别只在**判据来源**：那个来自 SDK 的 `onSurfaceReady()`，
     * 这个来自投屏页对 Surface 的真实像素拷贝。SDK 那条链在
     * `mLastReadyCountTime` / `mLastShownTime` 等内部状态不满足时会永久不回调
     * （已通过反编译 `VideoPlayerV2$1.onOutputBufferAvailable` 确认），
     * 而画面其实在正常播放。两条任一到达都算「有画面」。
     */
    override fun onFrameVisible() {
        AppLog.i("ICCOA: frame visible (pixel probe)")
        _state.update { it.copy(castReady = true, recovering = false, phase = Phase.Connected) }
    }

    /** 投屏页像素采样成功后回灌，用来解除 IccoaManager 的首屏看门狗。 */
    fun onPixelsVisible() = manager.onPixelsVisible()

    override fun onRecovering() {
        AppLog.i("ICCOA: recovering — first frame never arrived, restarting the session")
        _state.update { it.copy(recovering = true, castReady = false) }
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
    }

    /**
     * 手机端媒体信息。
     *
     * 目前只落到 [State.music] 供 UI 使用；车机侧要不要画封面/进度条是后续版本的事，
     * 但数据通道先打通，免得以后又要改一遍 SDK 接线。
     */
    override fun onMusicInfo(text: String?) {
        _state.update { it.copy(music = text) }
    }

    /** 手机端电话状态。 */
    override fun onCallState(text: String?) {
        _state.update { it.copy(callState = text) }
    }

    // ==================================================================
    // P2：车机 -> 手机 的操作入口
    // ==================================================================

    /**
     * 方控按键。
     *
     * @return true 表示这个键已经交给 ICCOA（调用方不要再本地处理）；
     *         false 表示没有对应语义或当前没在投屏，交回系统默认分发。
     */
    fun handleKey(event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN &&
            event.action != android.view.KeyEvent.ACTION_UP
        ) {
            return false
        }
        return manager.sendKey(event.keyCode, event.action, event.repeatCount)
    }

    /** 日夜模式同步。 */
    fun sendDayNight(night: Boolean) = manager.sendDayNight(night)

    /** 接听来电（方控「接听」键可用于这里）。 */
    fun answerCall(): Boolean = manager.answerCall()

    /** 挂断 / 拒接。 */
    fun hangUpCall(): Boolean = manager.hangUpCall()

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase) {
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
    }
}
