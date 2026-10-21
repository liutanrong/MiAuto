package com.miauto.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;
import com.ucar.vehiclesdk.UCarConfig;

import java.util.List;
import java.util.Random;

/**
 * MiAuto 车机端 ICCOA CarLink（小米 CarWith）SDK 生命周期管理器。
 *
 * <p>本类只管 SDK：初始化 / 广播 / 投屏 Surface / 回调转发。UI 与 BYD 输出通过
 * {@link Listener} 解耦（由 Activity 或 {@code IccoaNavigationBridge} 实现）。
 *
 * <p><b>★ init-once 状态机</b>：{@link UCarAdapter#init} 在 SDK 内部是「进程一次性」
 * （{@code sIsServiceInitialized} 守门）。重启只 {@code startAdvertise()}，绝不重复 init，
 * 否则 BLE 广播状态残留，手机扫不到、{@code onPinCode} 不再触发。
 * {@link #stop()} 断开但保留初始化标志。
 */
public class IccoaManager {

    private static final String TAG = "MiAuto/ICCOA";

    /** 事件回调。由 UI / 导航桥接实现。 */
    public interface Listener {
        void onLog(String msg);
        /** 已连接状态变化：0 断开 / 1 连接中 / 2 已连接 / 3 已建立 */
        void onConnectionState(int state);
        /**
         * 连接进度 0..100。
         *
         * <p>暴露它的唯一目的：让宿主能在进度过半时就准备好 Surface。ICCOA 的 RTSP
         * 协商需要 sink 端的 Surface 已存在，而 Surface 只在投屏页里才有 —— 如果等到
         * Connected 才打开投屏页，会形成死锁：没 Surface → 协商停在 80% → 永远不 Connected。
         */
        void onProgress(int progress);
        void onPinCode(String pinCode, String carDeviceName);
        void onCastStarted();
        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UCarAdapter adapter = null;
    private Surface surface = null;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 状态机 ----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    private volatile String lastPinCode = null;

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    // ★ 从 1.2 秒提高到 5 秒。断开一个 P2P 组之后，Android 的 wpa_supplicant 需要几秒
    //   才能真正拆除 group interface；期间重新 startAdvertise()，新会话会在 36 毫秒内
    //   被对端直接拒掉（progress 10 → 0 → 10003），而不是等 15 秒超时 —— 两种失败形态
    //   在日志里能明显区分。留足拆除时间，重连才稳定。
    private static final long RE_ADVERTISE_DELAY_MS = 5000L;

    /**
     * 配对码自动刷新周期。
     *
     * <p>车机一直在广播并不足以把手机叫回来：BLE 广播帧内容不变时，手机端 CarWith 不会重新
     * 弹出配对界面。用户一旦关掉那个弹窗，之后就必须手动去 CarWith 里找车机才能重连。
     * 荣耀车联 / HiCar 的车机端做法是周期性重播，本常量即那个周期。
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /** 会话是否正在进行（手机连接中或已连接）。由连接回调维护，看门狗据此避让。 */
    private volatile boolean sessionActive = false;

    /**
     * 会话结束后的重新广播。
     *
     * <p>车机端必须一直可被发现：手机断开后若不再广播，用户就得手动重启 App 才能重连。
     * 重新广播时会清掉旧配对码，手机因此走一次全新配对并下发【新的】配对码 —— 这就是
     * 「断开后自动刷新链接码」。
     */
    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady && adapter != null) {
            log("会话结束 → 重新广播（等待手机再次连接，届时下发新的配对码）");
            reAdvertise();
        }
    };

    private void scheduleReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, RE_ADVERTISE_DELAY_MS);
    }

    /**
     * 配对码看门狗：每隔 {@link #AUTO_REFRESH_INTERVAL_MS} 检查一次，仍未连上就重播一次。
     *
     * <p>这是「刷新出来的新码没人来连，之后就再也不刷新」的解法 —— 那种情况下手机端弹窗
     * 已经关掉，车机只能靠重播把它重新叫出来，否则用户点开 CarWith 也发现不了车机。
     *
     * <p>避让规则：手机正在连接或已连上（{@link #sessionActive}）、或画面已经投出去
     * （{@code castStarted}）时一律跳过，绝不打断进行中的会话。新码下发时计时会重置，
     * 保证用户拿到完整的 30 秒去输入。
     */
    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady || adapter == null) return;
        if (sessionActive || castStarted) {
            log("自动刷新：会话进行中，跳过本轮");
            scheduleAutoRefresh();
            return;
        }
        log("★ 自动刷新：超过 " + (AUTO_REFRESH_INTERVAL_MS / 1000) + " 秒未连接 → 重播以重新触发手机弹窗");
        reAdvertise();
        scheduleAutoRefresh();
    };

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void cancelAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
    }

    /**
     * 协商超时兜底。
     *
     * <p>原逻辑刻意不把 CONNECTING（newState==1）算作 sessionActive，理由是「协商卡死时
     * 让看门狗重播救回来」。但实测（vivo X200s / Android 10）暴露了反面：一旦进入
     * CONNECTING，30 秒看门狗照常 stop→start 重播，正在进行的鉴权被连根拔掉，
     * 日志里表现为「progress 10 → 自动刷新 → progress 0 + 10008」。
     *
     * <p>现在改成：CONNECTING 也置 sessionActive（不再打断协商），另设一个比看门狗更长的
     * 协商超时；超时仍未连上才放开 sessionActive 并重播。这样既不打断正常协商，
     * 也不会永久卡死。
     */
    private static final long CONNECTING_TIMEOUT_MS = 45_000L;

    private final Runnable connectingTimeoutRunnable = () -> {
        if (!sessionActive || castStarted) return;
        log("★ 协商超时（" + (CONNECTING_TIMEOUT_MS / 1000) + " 秒未建立连接）→ 强制重播");
        sessionActive = false;
        reAdvertise();
        scheduleAutoRefresh();
    };

    private void scheduleConnectingTimeout() {
        main.removeCallbacks(connectingTimeoutRunnable);
        main.postDelayed(connectingTimeoutRunnable, CONNECTING_TIMEOUT_MS);
    }

    private void cancelConnectingTimeout() {
        main.removeCallbacks(connectingTimeoutRunnable);
    }

    // ---- 屏幕尺寸 ----
    private int screenW = 1920, screenH = 720, videoW = 1920, videoH = 720;

    public IccoaManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void setScreenSize(int w, int h) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        // ★ 投屏画布上限跟随「视频分辨率」设置项（Settings.resolutionId）。
        //   默认 720p：1920x990@30 约 190 万像素/帧，H.264 码率 8~12 Mbps，而车机走的是
        //   SoftAP（2.4GHz，实际吞吐 20~30 Mbps 且抖动大），余量撑不住稳定 30fps。
        //   降到 720p 级（约 85 万像素）码率减半。想更清晰就去设置里切 1080p，想更流畅切 480p，
        //   不需要重新编译。
        int capW = 1280, capH = 720;
        try {
            int rid = new com.andrerinas.openheadunit.utils.Settings(appCtx).getResolutionId();
            switch (rid) {
                case 1: capW = 800;  capH = 480;  break;   // 480p
                case 3: capW = 1920; capH = 1080; break;   // 1080p
                case 4: capW = 2560; capH = 1440; break;   // 2K
                case 5: capW = 3840; capH = 2160; break;   // 4K
                default: break;                            // 0=auto / 2=720p
            }
        } catch (Throwable t) {
            log("读取分辨率设置失败，按 720p 处理: " + t);
        }
        float scale = Math.min(1f, Math.min((float) capW / screenW, (float) capH / screenH));
        videoW = Math.max(1, Math.round(screenW * scale));
        videoH = Math.max(1, Math.round(screenH * scale));
        // ★ 向上对齐到 16 的倍数。H.264 宏块是 16x16，1920x990 的 990 不是倍数
        //   （990/16=61.875），部分解码器在这种高度上只出首帧就不再出帧 ——
        //   现象正是「有第一屏、之后完全不动」。对齐到 992 不改观感，但让解码器走常规路径。
        videoW = Math.min(capW, ((videoW + 15) / 16) * 16);
        videoH = Math.min(capH, ((videoH + 15) / 16) * 16);
        log("投屏尺寸: 面板 " + screenW + "x" + screenH + " → 视频 " + videoW + "x" + videoH
                + "（上限 " + capW + "x" + capH + "，已按 16 对齐）");
    }

    public String getLastPinCode() { return lastPinCode; }

    /** Surface 就绪 / 变化（Activity 的 surfaceChanged 回调里调用）。 */
    public void setSurface(Surface s) {
        // 同一个 Surface 的重复回调（surfaceChanged 会再次进来）直接忽略：
        // SDK 自己会处理尺寸变化，这里再 startCast 一次只会打断正在进行的媒体协商。
        if (s != null && s == this.surface) return;
        this.surface = s;
        castRetry = 0;          // 新 Surface 到位，重试预算重置
        // ★ Surface 重建（旋转屏、切后台再回来、Activity 重建）后必须允许重新投。
        //   原实现只重置重试预算，但 tryStartCast() 首行就是 "castStarted 则 return"，
        //   而 castStarted 仍为 true —— 新 Surface 永远拿不到流，表现为永久黑屏。
        if (castStarted) {
            log("Surface 重建 → 重置 castStarted，重新 startCast");
            castStarted = false;
        }
        tryStartCast();
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    public void start() {
        log("=== 启动（ICCOA CarWith）===");
        // 每次开始新会话都重置触控日志预算，否则第一次调试用完 12 条之后，
        // 后续连接里的触摸将完全不留痕迹（曾经因此误判为「事件没到」）。
        touchLogBudget = 12;
        if (sdkInitialized && adapter != null) {
            log("重启：SDK 已初始化，仅重新广播");
            reAdvertise();
            return;
        }
        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        try {
            adapter = UCarAdapter.getInstance();
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("✗ getInstance/register 异常: " + t);
            return;
        }
        initSdk(config);
    }

    private void initSdk(UCarConfig config) {
        boolean posted = adapter.init(appCtx, config, new ICarInitCallback() {
            @Override
            public void onInitSuccess() {
                log("★ onInitSuccess()");
                sdkReady = true;
                sdkInitialized = true;   // ★ init-once 守门

                // ★ 打开 SDK 的 AOA(USB) 通道检测。
                //   SDK 默认关闭这个开关，而上游 MiAuto 从未调用过它 —— 结果是插线后
                //   只有基座残留的 Android Auto 握手在跑（日志里的 "Start Aap transport
                //   handshake"），ICCOA 自己的 libusbio.so 根本不会接管 USB 设备。
                //   打开后 SDK 会自行 UsbManager.requestPermission()，走 AOA 建链，
                //   完全不依赖 Wi-Fi P2P / SoftAP。
                try {
                    adapter.enableUsbDeviceDetection(true);
                    log("✓ enableUsbDeviceDetection(true) —— AOA 有线通道已启用");
                } catch (Throwable t) {
                    log("✗ enableUsbDeviceDetection 失败: " + t);
                }

                main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
            }

            @Override
            public void onInitFailed(int errCode) {
                log("✗ onInitFailed(" + errCode + ")"
                        + (errCode == 10001 ? " ← HandlerThread 启动失败" : ""));
            }
        });
        if (!posted) log("✗ init() 返回 false —— 任务未提交");
    }

    /**
     * 重播：先停旧广播，再启新广播。
     *
     * <p><b>必须先 {@code stopAdvertise()}</b> —— 对一条已经在广播中的链路重复调用
     * {@code startAdvertise()} 会被 SDK 直接忽略，广播帧内容不变，手机端因此不会重新发现
     * 车机、也就不会重新弹窗。只有「停-启」才是一次真正的重播。
     */
    private void reAdvertise() {
        if (adapter == null) return;
        try {
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("reAdvertise 重新注册异常: " + t);
        }
        try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(重播前) 异常: " + t); }
        sdkReady = true;
        castStarted = false;
        castRetry = 0;
        lastPinCode = null;
        doAdvertise();
    }

    /**
     * 用户主动「刷新配对码」：立刻重播一次，不必等看门狗的 30 秒周期。
     *
     * <p>与自动刷新走同一条路径，重播后看门狗重新计时。
     */
    public void refreshPairing() {
        log("=== 手动刷新配对码 ===");
        if (!sdkReady || adapter == null) {
            log("刷新失败：SDK 尚未就绪");
            return;
        }
        reAdvertise();
        scheduleAutoRefresh();
    }

    public void stop() {
        log("=== 停止 ===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        sessionActive = false;
        castStarted = false;
        sdkReady = false;
        lastPinCode = null;
        // 保留 sdkInitialized —— init() 进程一次性，重启只走 reAdvertise()。
    }

    /**
     * 仅断开当前会话（用户主动点「断开」），保持广播 —— 手机可立即重新连接。
     *
     * <p>与 {@link #stop()} 的区别：{@code stop()} 会停掉广播（相当于彻底关机位），
     * 本方法只拆会话，车机仍可被发现。
     */
    public void disconnectSession() {
        log("=== 断开当前会话（保持广播）===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
        }
        sessionActive = false;
        castStarted = false;
        lastPinCode = null;
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    // ==================================================================
    // 广播 / 投屏
    // ==================================================================

    private void doAdvertise() {
        if (adapter == null) return;
        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        tryStartCast();
    }

    /**
     * 尝试起投。
     *
     * <p>重试有上限：停车待机页没有 Surface，无上限重试会变成一个永不停止的 500ms 定时器
     * （待机页常驻，等于白耗电）。Surface 稍后就绪的常见情形由 {@link #setSurface} 兜底，
     * 这里的十秒预算只用于「Surface 已设但尚未 valid」的短暂窗口。
     */
    private void tryStartCast() {
        if (!sdkReady || castStarted || adapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        castRetry = 0;
        boolean ok = adapter.startCast(s, videoW, videoH);
        castStarted = ok;
        log((ok ? "✓ " : "✗ ") + "startCast(" + videoW + "x" + videoH + ") = " + ok
                + " surfaceValid=" + s.isValid()
                + " panel=" + screenW + "x" + screenH);
        if (ok) {
            main.post(listener::onCastStarted);
        }
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName);
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        @Override
        public void onConnectStateChanged(String deviceID, int oldState, int newState) {
            // ★ 带上 deviceID。多台手机同时在场时，光看状态码分不清是「同一台在重试」
            //   还是「不同设备在互相顶掉」——这两者的处理方式完全不同。
            log("onConnectStateChanged dev=" + shortId(deviceID)
                    + " " + stateName(oldState) + " -> " + stateName(newState));
            if (newState == 0 && oldState != 0) {
                // 会话真正结束（不是启动时的初始 DISCONNECTED）。清掉已过期的配对码并延时重新
                // 广播：手机端 CarWith 会重新发现车机并触发一次全新配对（新的 onPinCode）。
                sessionActive = false;
                lastPinCode = null;
                castStarted = false;
                cancelConnectingTimeout();
                scheduleReAdvertise();
                scheduleAutoRefresh();
            } else if (newState == 1) {
                // ★ 协商中：也要置 sessionActive，否则 30 秒看门狗会在鉴权进行到一半时
                //   stop→start 重播，把正在跑的会话连根拔掉（实测 vivo 上就是卡在这一步）。
                //   改用更长的 CONNECTING_TIMEOUT 兜底，超时才放开。
                sessionActive = true;
                main.removeCallbacks(reAdvertiseRunnable);
                scheduleConnectingTimeout();
            } else if (newState == 2 || newState == 3) {
                // 已连上：撤销任何待执行的重新广播，免得刚连上就被重启广播打断；看门狗也会因此空转。
                sessionActive = true;
                cancelConnectingTimeout();
                main.removeCallbacks(reAdvertiseRunnable);
            } else if (newState >= 10000) {
                // 10003 / 10004 / 10008 —— SDK 的 handleConnectFailed() 三个失败分支
                // （分别对应 USB / P2P / SoftAP 建链失败）。这些不是 0/1/2/3 状态，
                // 落到这里必须清掉会话标记，否则看门狗会一直以为「正在协商」而永不重播。
                log("✗ 连接失败码 " + newState + " → 交回看门狗，准备重播");
                sessionActive = false;
                castStarted = false;
                cancelConnectingTimeout();
                if (newState == 10008) {
                    // 10008 总是紧跟在 startAdvertise() 之后出现，是「本端刚重启广播，
                    // 上一个会话因此作废」的回声，不是手机侧的新失败。再触发一次重播
                    // 只会制造 1.2 秒级的自我循环（日志里 10003→重播→10008→重播）。
                    log("  （10008 为重播回声，跳过再次重播）");
                    scheduleAutoRefresh();
                } else {
                    scheduleReAdvertise();
                    scheduleAutoRefresh();
                }
            }
            final int st = newState;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            log("onConnectingProgress " + progress);
            main.post(() -> listener.onProgress(progress));
        }

        @Override
        public void onReconfigureWifiInfo(WifiOwnerConfig config) {
            log("onReconfigureWifiInfo ssid=" + (config == null ? "?" : config.getSsid()));
        }

        @Override
        public void onRethinkBluetoothConnect(boolean rethink) {
            log("onRethinkBluetoothConnect=" + rethink);
        }

        @Override
        public UCarCommon.WorkMode onSelectWorkMode(String deviceID, UCarCommon.WorkMode supported) {
            UCarCommon.WorkMode chosen;
            if (supported == null) {
                chosen = UCarCommon.WorkMode.NONE;
            } else if (supported.isSupportNormalCast()) {
                // ★ 必须优先 NormalCast（全屏镜像）。
                //
                //   原实现优先选 AppWindow / AppFullScreen，但 buildConfig() 里我们只声明了
                //   setSupportNormalCast(true)，后两者都是 false。supported 是**手机报告的能力**，
                //   只要手机支持应用窗口投屏就会选中 APP_WINDOW_CAST —— 车机却按全屏准备。
                //   后果正是「画面能出、能更新，但触摸坐标按全屏算，点哪都不中」。
                chosen = UCarCommon.WorkMode.NORMAL_CAST;
            } else if (supported.isSupportAppFullScreenCast()) {
                chosen = UCarCommon.WorkMode.APP_FULLSCREEN_CAST;
            } else if (supported.isSupportAppWindowCast()) {
                chosen = UCarCommon.WorkMode.APP_WINDOW_CAST;
            } else if (supported.isSupportDataTransfer()) {
                chosen = UCarCommon.WorkMode.DATA_TRANSFER;
            } else {
                chosen = UCarCommon.WorkMode.NONE;
            }
            log("onSelectWorkMode → " + chosen
                    + "（手机支持: normal=" + supported.isSupportNormalCast()
                    + " fullscreen=" + supported.isSupportAppFullScreenCast()
                    + " window=" + supported.isSupportAppWindowCast() + "）");
            return chosen;
        }
    };

    private final IPhoneDataListener phoneDataListener = new IPhoneDataListener() {
        @Override
        public void onNavigationInfoReceived(String phoneId, UCarCommon.NavigationInfo info) {
            listener.onNavigation(info);
        }

        @Override public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) { }
        @Override public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) { }
        @Override public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) { }
        @Override public void onPOIReceived(String phoneId, UCarCommon.POIAddress poi) { }
        @Override public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) { }

        @Override
        @SuppressWarnings("rawtypes")
        public void onAppListStateChanged(String phoneId, List list, UCarCommon.AppListState state) { }

        @Override
        public void onAppStateChanged(String phoneId, int state, boolean flag,
                                      int a, int b, UCarCommon.AppState appState) { }

        @Override
        public UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type) {
            return null;
        }
    };

    // ==================================================================
    // 配置构造（SDK 硬校验，越界即抛）
    // ==================================================================

    private UCarConfig buildConfig() {
        byte[] carId = generateCarId();
        if (carId == null || carId.length != 6) {
            carId = new byte[6];
            new Random().nextBytes(carId);
            log("! carId 长度异常，强制随机 6 字节");
        }
        android.util.DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi;
        // ★ dpi 不再写死 320。12.8" / 1920x990 的密度约 172dpi，写死 320 会让手机端
        //   按 320dpi 布局，车机上元素明显偏小。改成跟随面板真实密度。
        int realDpi = clamp(densityDpi, 120, 640);
        int dpi = realDpi;
        // ★ fps 不再硬编码。跟随设置里的 FPS Limit（键名 fps-limit），默认 60 与原行为一致；
        //   骁龙 665 这类平台上 1080p60 编码余量不足，用户可以在设置里降到 30，
        //   不需要为此单独编译一版。
        int fps = clamp(readIntSetting("fps-limit", 60), 15, 60);
        int sw = Math.max(1, screenW), sh = Math.max(1, screenH);
        int vw = Math.max(1, videoW), vh = Math.max(1, videoH);

        UCarCommon.WorkMode workMode = new UCarCommon.WorkMode.Builder()
                .setSupportNormalCast(true)
                .setSupportAppFullScreenCast(false)
                .setSupportAppWindowCast(false)
                .build();

        // ★ 通道能力只能声明一种，否则 SDK 会按「手机期望值」二选一，且失败后没有回退。
        //   反编译 ShareLinkService.createWifiConnection(expectConnectType)：
        //     手机期望 AP(1002) + 车机声明 both(1003) → 关 P2P，createSoftAp
        //     手机期望 P2P(1001) + 车机声明 both(1003) → 关 AP，createGroupForClient
        //     手机期望值与车机能力对不上（无对应分支）→
        //        notifyConnectFailed(3) → 对外 10003，日志 "the phone don't support wireless connection"
        //   原先两个都写 true，等于把选择权交给手机；一旦选中的那条路在本机不可用，
        //   就停在 progress 10 等 15 秒，然后 10003。
        //   这里改为跟随设置里的 Network transport：
        //     0 = WiFi Direct → 只声明 P2P
        //     1 = Hotspot     → 只声明 SoftAP（需系统热点处于开启状态）
        boolean useP2p = true, useAp = true;
        try {
            int transport = readIntSetting("native-ap-transport", 1);
            useP2p = (transport == 0);
            useAp = !useP2p;
            log("通道能力: transport=" + transport + " → P2P=" + useP2p + " SoftAP=" + useAp);
        } catch (Throwable t) {
            log("读取 transport 失败，退回 both: " + t);
        }

        return new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(useP2p)
                .setSupportSoftAP(useAp)
                .setSupportCamera(false)
                .setSupportHevc(false)
                .setSupportMic(true)
                .setUseCustomAudioRecord(false)
                .setSupportStereoRecord(false)
                .setSupportLowLatencyDecodingMode(false)
                .setSupportPOINav(false)
                .setCcdFilePath("ccd.data")
                .setSupportedWorkModes(workMode)
                .build();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 读取设置项，读不到就用默认值。
     * ICCOA 与设置页共享同一份 SharedPreferences（名称为 "settings"），
     * 这样网络通道、帧率等参数都能由用户在界面上调整，不必为此单独编译。
     */
    private int readIntSetting(String key, int def) {
        try {
            return appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private byte[] generateCarId() {
        SharedPreferences prefs = appCtx.getSharedPreferences("car_id_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("car_id_key", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                byte[] cached = Base64.decode(saved, Base64.NO_WRAP);
                if (cached != null && cached.length == 6) return cached;
                log("carId 缓存长度≠6，作废重生成");
            } catch (Throwable t) {
                log("carId 缓存解码失败: " + t);
            }
        }
        // ★ 不再用 BluetoothAdapter.getAddress()。Android 6 起非系统应用拿到的永远是
        //   02:00:00:00:00:00，而它「长度合法」，于是被当成 carId 存进缓存 —— 结果每台
        //   车机广播同一个标识，手机端 Peer 表也会互相撞车。改用 ANDROID_ID 派生。
        byte[] raw = deriveCarIdFromAndroidId();
        if (raw == null || raw.length != 6) {
            raw = new byte[6];
            new Random().nextBytes(raw);
        }
        prefs.edit().putString("car_id_key", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return raw;
    }

    /** 从 ANDROID_ID 稳定派 6 字节 carId；取不到返回 null，由调用方退回随机。 */
    private byte[] deriveCarIdFromAndroidId() {
        try {
            String id = android.provider.Settings.Secure.getString(
                    appCtx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty() || "9774d56d682e549c".equals(id)) return null;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes("UTF-8"));
            byte[] out = new byte[6];
            System.arraycopy(digest, 0, out, 0, 6);
            return out;
        } catch (Throwable t) {
            log("ANDROID_ID 派生 carId 失败: " + t);
            return null;
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private byte[] getBluetoothMac() {
        byte[] macBytes = new byte[6];
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            String mac = (a == null) ? null : a.getAddress();
            if (mac != null) {
                String[] seg = mac.split(":");
                if (seg.length == 6) {
                    for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(seg[i], 16);
                    return macBytes;
                }
            }
        } catch (Throwable t) {
            log("读蓝牙 MAC 被拒（缺 BLUETOOTH_CONNECT？）: " + t);
        }
        new Random().nextBytes(macBytes);
        return macBytes;
    }

    // ==================================================================
    // 触控回传（UIBC）
    // ==================================================================

    /**
     * 把车机屏幕上的触摸事件回传给手机。
     *
     * <p>SDK 的方法签名是 {@code sendTouchEvent(action, count, trackIds[], xs[], ys[])}，
     * 内部会把坐标从「display 尺寸」（即 {@code setScreenWidth/Height} 传进来的值）映射到
     * 「source 尺寸」（手机屏幕），再组 JSON、加密、交给 {@code SinkNative.onUibcEvent()}。
     *
     * <p>三个前置条件是 SDK 自己检查的，不满足时只打日志并返回 false：
     * <ul>
     *   <li>{@code mIsEncryptionSettled} —— 加密尚未就绪（连接未完成）</li>
     *   <li>{@code mSourceWidth/Height > 0} —— 源尺寸还没初始化</li>
     *   <li>三个数组长度必须都等于 {@code count}</li>
     * </ul>
     *
     * @param e     车机侧的触摸事件
     * @param viewW 承载画面的 View 宽度（像素）
     * @param viewH 承载画面的 View 高度（像素）
     */
    public void sendTouch(android.view.MotionEvent e, int viewW, int viewH) {
        if (adapter == null || e == null) return;
        if (viewW <= 0 || viewH <= 0) return;

        final int action = e.getActionMasked();
        // ★ 节流。车机触摸面板采样率通常 100~120Hz，逐事件转发会把 UIBC 通道塞满
        //   （每帧 MOVE 都要组 JSON + 加密 + 走 RTP），在 2.4GHz 链路上反而把延迟推高。
        //   把 MOVE 压到约 60Hz，DOWN / UP / CANCEL 一律不节流，保证点击手感。
        final long now = android.os.SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_MOVE && now - lastTouchSentMs < 16L) {
            return;
        }
        lastTouchSentMs = now;

        final int count = e.getPointerCount();
        final int[] ids = new int[count];
        final int[] xs = new int[count];
        final int[] ys = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = e.getPointerId(i);
            // ★ 归一化到「投屏内容」的坐标系（videoW x videoH），不是面板坐标系。
            //   UIBC 的坐标基准是镜像画面的尺寸：SDK 内部按 displayWidth → sourceWidth 映射，
            //   而 displayWidth 对应的是视频画布。之前用 screenW(2800) 传，视频只有 1280 宽，
            //   映射后坐标全部越界，手机收到也落不到任何控件上 —— 表现就是「点了等于没点」。
            xs[i] = Math.round(e.getX(i) * videoW / (float) viewW);
            ys[i] = Math.round(e.getY(i) * videoH / (float) viewH);
            // 钳制到画面内。车机触摸屏的边缘值可能略超出 View 范围（面板黑边、
            // 系统手势区），越界坐标会被对端当成非法事件丢弃。
            xs[i] = Math.max(0, Math.min(videoW - 1, xs[i]));
            ys[i] = Math.max(0, Math.min(videoH - 1, ys[i]));
        }
        try {
            boolean ok = adapter.sendTouchEvent(action, count, ids, xs, ys);
            // ★ 成功也要记一条。之前只在失败时打日志，结果「触摸没反应」时无从判断
            //   到底是事件没到、还是到了但被 SDK 拒、还是送到了手机但手机没执行。
            //   预算 12 条足够看清一次按下-移动-抬起，又不会把诊断报告冲掉。
            if (touchLogBudget > 0) {
                touchLogBudget--;
                log("触控回传 action=" + action + " count=" + count
                        + " view=" + viewW + "x" + viewH
                        + " video=" + videoW + "x" + videoH
                        + " p0=(" + xs[0] + "," + ys[0] + ")"
                        + " → " + (ok ? "已发送" : "被拒"));
            }
        } catch (Throwable t) {
            if (touchLogBudget > 0) {
                touchLogBudget--;
                log("sendTouchEvent 异常: " + t);
            }
        }
    }

    /** 触控失败日志的预算：避免每帧 MOVE 都刷屏把诊断报告冲掉。 */
    private int touchLogBudget = 12;

    /** 上一次转发 MOVE 的时刻，用于把触摸频率压到约 60Hz。 */
    private long lastTouchSentMs = 0L;

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private void safe(String where, Runnable r) {
        try { r.run(); } catch (Throwable t) { log("!!! " + where + " 异常: " + t); }
    }

    /** deviceID 往往很长，日志里只留前 8 位，够用来区分不同手机。 */
    private static String shortId(String id) {
        if (id == null || id.isEmpty()) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String stateName(int s) {        switch (s) {
            case 0: return "DISCONNECTED";
            case 1: return "CONNECTING";
            case 2: return "CONNECTED";
            case 3: return "ESTABLISHED";
            default: return "STATE_" + s;
        }
    }
}
