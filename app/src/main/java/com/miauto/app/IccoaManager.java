package com.miauto.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.IAppSurfaceStateListener;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.andrerinas.openheadunit.BuildConfig;
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
        /**
         * 画面真的出现在屏幕上了（像素级确认，不是 SDK 回调）。
         *
         * <p>为什么需要它：反编译 {@code VideoPlayerV2$1.onOutputBufferAvailable} 可见
         * {@code onSurfaceReady()} 的触发条件是
         * <pre>
         *   mNeedRender == true
         *   &amp;&amp; mIsSurfaceAlReady == false
         *   &amp;&amp; mLastReadyCountTime != -1
         *   &amp;&amp; mLastReadyCountTime &gt;= presentationTimeUs
         * </pre>
         * 其中 {@code mLastReadyCountTime} 只在「输入侧第一帧且 mLastShownTime == 0」时才被赋值。
         * 只要任一条件不成立，**画面已经在放，回调却永远不来** —— 上层就会一直以为黑屏。
         * 所以真正的判据必须由我们自己采样 Surface 像素得到。
         */
        void onFrameVisible();
        /**
         * 第一帧真正解码出来（SDK 的 onSurfaceReady）。
         *
         * <p>它和 {@link #onCastStarted()} 不是一回事：后者只表示 startCast 调用被接受，
         * 那时可能还在协商；只有这一条才代表"画面已经出来了"，UI 上该把提示收掉的依据是它。
         */
        void onFirstFrame();
        /** 首场会话拿不到首帧、正在自动重建（UI 可据此提示"重新建立画面"）。 */
        void onRecovering();
        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);

        /**
         * 手机端媒体信息（曲目 / 播放状态）。
         *
         * <p>默认空实现：这两个回调只是把 SDK 已经送来的数据往上抛一层，
         * 没接住也不该影响投屏本身。
         */
        default void onMusicInfo(String text) { }

        /** 手机端电话状态（空闲 / 来电 / 通话中）。默认空实现，理由同上。 */
        default void onCallState(String text) { }

        /**
         * 手机端连接异常丢失（0.3.7 新增，僵死画面防护）。
         *
         * <p>SDK 状态机出现 {@code dev=-1 → STATE_-1}（设备标识都拿不到的断链）且
         * 画面曾经投出——这是"手机端已经不在了"的特征，车机画面会停在最后一帧。
         * 宿主（投屏页）收到后应退出，让等待页重新广播。
         */
        default void onLinkLost() { }

        /** 当前数据通道的可读描述（"USB AOA 有线" / "P2P Wi-Fi 5GHz" 等）。默认空实现。 */
        default void onTransportLabel(String label) { }
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UCarAdapter adapter = null;
    private Surface surface = null;
    /** 上一次真正用于 startCast 的 Surface 尺寸，用于区分「同一个 Surface 的重复回调」与「尺寸变化」。 */
    private int surfaceW = -1, surfaceH = -1;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 状态机 ----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    /** 上一次 init() 时实际采用的通道（native-ap-transport 的值），用于检测配置变更。 */
    private int appliedTransport = Integer.MIN_VALUE;

    /** SDK 是否已确认有画面（SDK 回调或像素采样，任一先到）。 */
    private volatile boolean surfaceReady = false;
    /** 等不到首帧时的重建次数，防止死循环。 */
    private int castRebuildCount = 0;
    /** 本进程内是否成功出过一次画面，用来区分「冷启动首连」与「中途失效」。 */
    private volatile boolean everSurfaceReady = false;

    // ---- 单次会话的诊断计时（一次上报，避免来回抓包） ----
    private long tStartCast = 0L;
    private long tProgress90 = 0L;
    private long tProgress100 = 0L;
    private long tSurfaceReadyAt = 0L;
    private int appliedFps = 0;
    private long lastSegMs = -1L;
    private long lastFirstFrameMs = -1L;
    /** true 表示 lastFirstFrameMs 是「距 startCast」，false 表示「距 progress100」。 */
    private boolean firstFrameFromStart = false;
    /** 最近一次收到的协商进度，用于在 startCast 日志里确认「是否早于媒体协商」。 */
    private volatile int lastProgress = 0;
    /** 会话统计（首帧耗时、重连次数、通道、手机型号），汇总与诊断报告共用。 */
    private final IccoaSessionStats stats = new IccoaSessionStats();
    /** 触控映射与节流。 */
    private final IccoaTouch touch = new IccoaTouch();
    /**
     * 每次会话「触摸被拒 → 懒补 → 重发」的次数预算。
     * 反射调用有成本，且被拒往往意味着整个会话的 source size 都没初始化，
     * 补一次就够；给 3 次是防万一某次反射执行失败。
     */
    private int uibcFixBudget = 3;
    /** USB 优先：插着配件时停掉蓝牙广播，让手机只能走 AOA。 */
    private boolean usbPriority = true;
    private boolean usbReceiverRegistered = false;

    /**
     * USB 配件插拔。
     *
     * <p>为什么要自己监听：无线一旦握手成功，SDK 会立刻 {@code stopAdvertise()}
     * （反编译 {@code UCarAdapter$6.onReceivedClientAddress}），USB 再没有参与机会。
     * 所以「USB 优先」只能靠**在手机挑通道之前把无线关掉**来实现 ——
     * 插入时停广播，拔出时恢复。
     */
    private final android.content.BroadcastReceiver usbReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    boolean accessory = android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED
                            .equals(action);
                    boolean deviceAttach = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
                            .equals(action);
                    boolean deviceDetach = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
                            .equals(action);
                    if (accessory || deviceAttach) {
                        log("USB 接入（" + (accessory ? "accessory" : "device") + "）");
                        // 0.3.11：isUsbAttached() 把任何 UsbDevice 都算「手机插线」，
                        // U 盘/行车记录仪也会关掉秒断。把 VID:PID 打出来便于排除干扰项。
                        try {
                            android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager)
                                    appCtx.getSystemService(Context.USB_SERVICE);
                            java.util.HashMap<String, android.hardware.usb.UsbDevice> devs =
                                    um == null ? null : um.getDeviceList();
                            StringBuilder sb = new StringBuilder();
                            if (devs != null) {
                                for (android.hardware.usb.UsbDevice d : devs.values()) {
                                    sb.append(String.format("%04X:%04X(%s) ",
                                            d.getVendorId(), d.getProductId(), d.getProductName()));
                                }
                            }
                            log("USB 设备列表: " + (sb.length() == 0 ? "(空)" : sb.toString().trim()));
                        } catch (Throwable ignored) {
                        }
                        applyUsbPriority(true);
                    } else if (deviceDetach) {
                        log("USB 设备已断开");
                        applyUsbPriority(false);
                    }
                }
            };

    /**
     * USB 优先的实际执行（0.3.7 抽出）。
     *
     * <p>detach 带 2.5 秒去抖：实测 vivo 插上线后 USB 会在 1~2 秒内 ATTACH/DETACH
     * 连续抖动（手机端重枚举或线材接触），逐事件恢复广播会让手机在两条通道之间
     * 反复横跳。延迟恢复、attach 取消恢复，抖动就只表现为一次状态切换。
     */
    private void applyUsbPriority(boolean attached) {
        main.removeCallbacks(usbDetachDebounce);
        if (!usbPriority || adapter == null) {
            return;
        }
        if (attached) {
            if (sessionActive || castStarted) {
                log("USB 优先：当前会话进行中，不打断（下次插拔或重连时生效）");
                return;
            }
            boolean ok;
            try {
                ok = adapter.stopAdvertise();
            } catch (Throwable t) {
                ok = false;
            }
            log("USB 优先：停止蓝牙广播，只等数据线 " + (ok ? "成功" : "失败"));
        } else {
            main.postDelayed(usbDetachDebounce, 2500L);
        }
    }

    private final Runnable usbDetachDebounce = new Runnable() {
        @Override
        public void run() {
            if (!usbPriority || adapter == null || isUsbAttached()) {
                return;
            }
            if (sessionActive || castStarted) {
                return;
            }
            boolean ok;
            try {
                ok = adapter.startAdvertise();
            } catch (Throwable t) {
                ok = false;
            }
            log("USB 优先：配件已拔出（去抖确认），恢复蓝牙广播 " + (ok ? "成功" : "失败"));
        }
    };

    /** 僵死画面防护：2 秒确认窗到期后仍未恢复 → 断开会话并通知投屏页退出。 */
    private boolean linkLostArmed = false;

    private final Runnable linkLostRunnable = new Runnable() {
        @Override
        public void run() {
            linkLostArmed = false;
            if (castStarted || sessionActive) {
                log("✗ 手机端连接丢失（dev=-1 → STATE_-1 持续 2s）→ 断开会话，退出投屏页");
                try { disconnectSession(); } catch (Throwable t) { log("断开异常: " + t); }
                try { listener.onLinkLost(); } catch (Throwable t) { /* 忽略 */ }
            }
        }
    };

    /** USB 优先的兜底等待：到点还没建链就恢复广播。 */
    private static final long USB_PRIORITY_FALLBACK_MS = 10_000L;

    private final Runnable usbPriorityFallback = new Runnable() {
        @Override
        public void run() {
            if (!usbPriority || adapter == null) {
                return;
            }
            if (sessionActive || castStarted) {
                return;
            }
            log("USB 优先：10 秒内没有 AOA 建链 → 回退到蓝牙广播（手机可能不走数据线）");
            boolean ok;
            try {
                ok = adapter.startAdvertise();
            } catch (Throwable t) {
                ok = false;
            }
            log("USB 优先：回退广播 " + (ok ? "成功" : "失败"));
            if (ok) {
                schedulePinWatchdog();
            }
            tryStartCast();
        }
    };

    private void registerUsbReceiver() {
        if (usbReceiverRegistered) {
            return;
        }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            // ★ 0.3.7：手机插车机时是 UsbDevice（MTP 模式），不进 accessory 事件——
            //   原先只监听 ACCESSORY_* 等于永远收不到"插上线"这件事。
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
            appCtx.registerReceiver(usbReceiver, f);
            usbReceiverRegistered = true;
        } catch (Throwable t) {
            log("USB 插拔监听注册失败: " + t);
        }
    }

    private void unregisterUsbReceiver() {
        if (!usbReceiverRegistered) {
            return;
        }
        try {
            appCtx.unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        usbReceiverRegistered = false;
    }

    /** P2 车联能力开关（方控 / 日夜 / 车况），启动时读一次。 */
    private boolean carLinkEnabled = true;
    private boolean gpsUplinkEnabled = true;
    /** 音乐信息的日志预算：播放时可能每秒来好几条，刷屏会淹掉投屏本身的记录。 */
    private int musicLogBudget = 6;

    // ---- 「先空配、后交接」：让首场会话也走 setOutputSurface 这条稳的路 ----
    /**
     * 反编译 {@code CastManager.startCast}：
     * <pre>
     *   if (mVideoPlayer == null) mVideoPlayer = new VideoPlayerV2(...);
     *   mVideoPlayer.init(surface, w, h, ...);
     *   if (!mVideoPlayer.isStart()) mVideoPlayer.start();
     *   mVideoPlayer.enableRender(true);
     * </pre>
     * 而 {@code VideoPlayerV2.init} 分两条路：
     * <ul>
     *   <li>{@code !mIsConfigured} → {@code MediaFormat.createVideoFormat(mime,w,h)} +
     *       {@code codec.configure(format, surface)} + {@code mIsFirstLaunch = true} +
     *       {@code mVideoQueueManager.clearDataIfNeeded()} ← <b>首场必走这条</b></li>
     *   <li>{@code mIsConfigured} → 只 {@code codec.setOutputSurface(surface)} ← <b>第二场走这条</b></li>
     * </ul>
     * 日志里稳定的规律是「首场黑、第二场好」，与这两条分支完全对应 —— 问题不在手机端，
     * 而在第一次 configure 把解码器绑到了「协商还没完成」的早期状态。
     *
     * <p>所以这里主动制造「第二场」的条件：第一次投屏时先用一块 <b>与视频同尺寸</b> 的
     * 离屏 Surface 完成 configure，300ms 后再把真实 Surface 交接过去 —— 走的就是
     * {@code setOutputSurface}，也就是每次都能出画面的那条分支。
     *
     * <p>尺寸必须与 {@code videoW x videoH} 一致：之前用 2x2 的 ImageReader 预热，
     * configure 出来的 format 与真实码流差着三个数量级，解码器直接不出帧，这就是
     * 那版预热「执行了但没用」的原因。
     */
    private boolean handoffPrimed = false;
    private SurfaceTexture primeTexture = null;
    private Surface primeSurface = null;
    /** 空配开始的时刻。用来防止 surfaceChanged 抢在 configure 完成前触发交接。 */
    private long primeStartedAt = 0L;
    /**
     * 是否仍处在「空配 → 真实 Surface」的过渡期。
     *
     * <p>这段时间里解码器的输出面是离屏的那块，任何 {@code onSurfaceReady} 都不代表
     * 车机屏幕上有了画面。不屏蔽掉的话，界面会在真正出帧之前就撤掉过渡遮罩，
     * 用户看到的反而是一段纯黑 —— 比等久一点更糟。
     */
    private volatile boolean primingPhase = false;

    /** 每次会话只打印一次「实际走的哪条通道」。 */
    private volatile boolean connectTypeLogged = false;
    /** 最近一次真实设备 ID（SDK 在会话切换时会给 "-1"，那个查不到设备）。 */
    private volatile String lastDeviceId = null;
    private volatile String lastPinCode = null;

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    // ★ 从 1.2 秒提高到 5 秒。断开一个 P2P 组之后，Android 的 wpa_supplicant 需要几秒
    //   才能真正拆除 group interface；期间重新 startAdvertise()，新会话会在 36 毫秒内
    //   被对端直接拒掉（progress 10 → 0 → 10003），而不是等 15 秒超时 —— 两种失败形态
    //   在日志里能明显区分。留足拆除时间，重连才稳定。
    private static final long RE_ADVERTISE_DELAY_MS = 5000L;
    /** 首屏失败后的重播延时：没有稳定数据面要拆，不必等 5 秒。 */
    private static final long WATCHDOG_RE_ADVERTISE_DELAY_MS = 1000L;

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
        scheduleReAdvertise(RE_ADVERTISE_DELAY_MS);
    }

    /**
     * 指定延时的重播。
     *
     * 正常断线（用户点「断开」、或远场断开）用 5 秒：P2P 组的 group interface 拆除要几秒，
     * 太早重播会让手机撞到半拆除的组，36 毫秒内被直接拒掉。
     *
     * 但「首屏看门狗」这条路不一样——那条会话从头到尾没出过一帧，手机端也停在它自己的
     * 启动页，没有稳定的数据面要拆，等 5 秒纯属浪费。日志实测：重播之后 2.2 秒手机就回来了，
     * 所以这条分支只等 2 秒。
     */
    private void scheduleReAdvertise(long delayMs) {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, delayMs);
        log("计划重播：+" + delayMs + "ms");
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
        // ★ 0.3.11：castStarted 不再短路。起投后协商卡住（progress<90）的场景以前
        //   没有任何计时器，黑屏可无限持续；只要画面没确认过就允许超时救援。
        if (!sessionActive || surfaceReady) return;
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

    /** 离屏预热 Surface 交给真实 Surface 之前的缓冲时间：够 configure + start 完成。 */
    private static final long PRIME_HANDOFF_DELAY_MS = 300L;

    /**
     * 用一块「与视频同尺寸」的离屏 Surface 先把解码器 configure 掉。
     *
     * <p>这是本版的核心修法。反编译证据：{@code VideoPlayerV2.init()} 只有
     * {@code mIsConfigured == false} 时才会执行 {@code MediaCodec.configure(format, surface)}
     * 并把 {@code mIsFirstLaunch} 置 true；之后任何一次 {@code startCast} 都只调
     * {@code setOutputSurface}。而「首场黑、第二场好」正是这两条分支的分界。
     *
     * <p>所以第一次投屏时，先拿一块尺寸正确的 {@link SurfaceTexture} 离屏 Surface 走一遍
     * configure，再在 {@link #PRIME_HANDOFF_DELAY_MS} 后把真实 Surface 交过去 —— 真实会话
     * 走的就是 {@code setOutputSurface}，与第二场完全同路径。
     *
     * <p><b>尺寸必须等于 videoW x videoH</b>。早前那版预热用 2x2 的 ImageReader，configure
     * 出的 format 与真实码流差三个数量级，解码器不出帧，所以「执行了却没效果」。
     *
     * @return true 表示 configure 已被消耗掉，调用方应在延时后交接真实 Surface
     */
    private boolean primeDecoderOffscreen() {
        if (adapter == null) return false;
        // ★ 0.3.11：真实投屏已在进行时绝不做空配 —— 否则 startCast(primeSurface) 会把
        //   解码器输出面切到离屏，车机画面瞬间黑掉（doAdvertise 的 +600ms 延迟任务
        //   就可能撞上这个窗口）。
        if (castStarted) {
            log("空配跳过：真实投屏已在进行（避免抢走输出面）");
            return false;
        }
        try {
            if (primeSurface != null) return true;   // 已备好，直接交接
            primeTexture = new SurfaceTexture(false);
            primeTexture.setDefaultBufferSize(videoW, videoH);
            // 必须持续消费帧：SurfaceTexture 不 updateTexImage 的话 BufferQueue 很快塞满，
            // 解码器会卡在 releaseOutputBuffer 上，真实会话反而更慢。
            primeTexture.setOnFrameAvailableListener(st -> {
                try {
                    st.updateTexImage();
                } catch (Throwable ignored) {
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            primeSurface = new Surface(primeTexture);
            primingPhase = true;
            primeStartedAt = System.currentTimeMillis();
            boolean ok = adapter.startCast(primeSurface, videoW, videoH, surfaceStateListener);
            log((ok ? "✓ " : "✗ ") + "离屏空配 startCast(" + videoW + "x" + videoH + ") = " + ok
                    + "，+" + PRIME_HANDOFF_DELAY_MS + "ms 后交接真实 Surface");
            if (!ok) primingPhase = false;
            return ok;
        } catch (Throwable t) {
            log("离屏空配失败（退回直接投屏，行为同旧版）: " + t);
            releasePrime();
            return false;
        }
    }

    /** 释放空配用的离屏 Surface。真实 Surface 接手后它就没用了。 */
    private void releasePrime() {
        try {
            if (primeSurface != null) primeSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            if (primeTexture != null) primeTexture.release();
        } catch (Throwable ignored) {
        }
        primeSurface = null;
        primeTexture = null;
    }

    /**
     * 打印手机在 ClientHello 里声明的实际通道。
     *
     * 反编译 UCarConnectProxy$2.receivedClientHello：手机传一个字符串，SDK 据此写
     * MDevice.connectType ——
     *   "usb"      → 2  全通道走 USB（AUTH/CERT/CMD/CAST_RTSP/CAST_RTP/AUDIO/UIBC/SENSOR
     *                    经 AOA bulk 多路复用，完全不经 Wi-Fi）
     *   "wifi_p2p" → 1
     *   "wifi_ap"  → 3
     * 所以这一行是判断「USB 到底有没有被用上」的唯一可信依据，比插没插线可靠。
     */
    private void logConnectType(String phoneId) {
        if (connectTypeLogged) return;
        connectTypeLogged = true;
        try {
            if (adapter == null) return;
            // ★ getConnectType(String) 在查不到设备时**返回 1**（默认值），这会让
            //   "没查到" 看起来像 "走的是 P2P"。所以先用 getMobileDevice 判断设备是否存在，
            //   存在才读它的 connectType；会话切换瞬间 phoneId 常是 "-1"，需要用缓存的上一个。
            String id = phoneId;
            UCarCommon.MobileDevice dev = adapter.getMobileDevice(id);
            if (dev == null && lastDeviceId != null) {
                id = lastDeviceId;
                dev = adapter.getMobileDevice(id);
            }
            if (dev == null) {
                log("[通道判定] 未找到设备（phoneId=" + phoneId + " lastId=" + lastDeviceId + "），本次不判定");
                return;
            }
            int ct = dev.getConnectType();
            stats.connectType = ct;
            try {
                // MobileDevice 只有 getModel / getProductName，没有通用的 getName。
                String model = dev.getModel();
                stats.phoneModel = (model == null || model.isEmpty()) ? dev.getProductName() : model;
                if (stats.phoneModel == null) {
                    stats.phoneModel = "";
                }
            } catch (Throwable ignored) {
            }
            String name;
            switch (ct) {
                case 2:  name = "USB（AOA 全通道）"; break;
                case 1:  name = "WiFi Direct (P2P)"; break;
                case 3:  name = "WiFi SoftAP"; break;
                default: name = "默认/未知"; break;
            }
            // 补两项实锤：车机 Wi-Fi 是否开着、有没有 p2p 接口。
            //   connectType 是手机"声明"的，接口列表是车机"实际"的 —— 两者对不上时，
            //   以接口为准。有 p2p0 就说明视频真的跑在无线直连上；只有 wlan0/usb0 才是走线。
            StringBuilder extra = new StringBuilder();
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        appCtx.getSystemService(android.content.Context.WIFI_SERVICE);
                extra.append(" wifiEnabled=").append(wm != null && wm.isWifiEnabled());
            } catch (Throwable ignored) {
            }
            try {
                StringBuilder ifs = new StringBuilder();
                java.util.Enumeration<java.net.NetworkInterface> en =
                        java.net.NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    java.net.NetworkInterface ni = en.nextElement();
                    String n = ni.getName();
                    if (n != null && (n.startsWith("p2p") || n.startsWith("wlan") || n.startsWith("rndis"))) {
                        ifs.append(n).append(',');
                    }
                }
                extra.append(" ifaces=").append(ifs.length() == 0 ? "(none)" : ifs.toString());
            } catch (Throwable ignored) {
            }
            log("[通道判定] dev=" + id + " connectType=" + ct + " → " + name + " |" + extra);
        } catch (Throwable t) {
            log("[通道判定] 读取失败: " + t);
        }
    }

    /**
     * 每次会话结束时打一行汇总。
     *
     * <p>内容由 {@link IccoaSessionStats#summary()} 统一生成：除了原来就有的分辨率、
     * 协商耗时、首帧耗时，现在还带上「本进程会话数 / 重连次数 / 实际通道 / 手机型号」——
     * 以前排查时要人工从几百行日志里数这些，现在一行就够。
     */
    private void logSessionSummary(String reason) {
        stats.fps = appliedFps;
        if (stats.surfaceW <= 0) {
            stats.surfaceW = videoW;
            stats.surfaceH = videoH;
        }
        log("[诊断汇总] " + reason + " | " + stats.summary());
    }

    public void onSurfaceDestroyed() {
        logSessionSummary("投屏页关闭");
        this.surface = null;
        this.surfaceW = -1;
        this.surfaceH = -1;
        castStarted = false;
        surfaceReady = false;
        castRetry = 0;
        // 注意：**不重置 handoffPrimed**。
        //   VideoPlayerV2 挂在 CastManager 上、进程内不销毁，mIsConfigured 一旦为 true
        //   就一直为 true。此时再空配一次，startCast 会走 setOutputSurface 把画面切到
        //   离屏 Surface 上 —— 等于把一条本来正常的链路黑一下。所以空配只做一次。
        primingPhase = false;
        cancelEarlyBail();
        cancelCastStallWatchdog();
        logCapabilities();
        cancelGpsTick();
        main.removeCallbacks(surfaceWatchdog);
        log("Surface 已销毁 → 状态清空");
    }

    /**
     * 投屏页通过像素采样确认「画面真的出来了」。
     *
     * <p>为什么不直接依赖 {@code onSurfaceReady()}：反编译
     * {@code VideoPlayerV2$1.onOutputBufferAvailable} 的判定链是
     * <pre>
     *   mNeedRender &amp;&amp; !mIsSurfaceAlReady
     *   &amp;&amp; mLastReadyCountTime != -1
     *   &amp;&amp; mLastReadyCountTime &gt;= presentationTimeUs
     * </pre>
     * 而 {@code mLastReadyCountTime} 只在输入侧「第一帧 且 mLastShownTime == 0」时才赋值。
     * 这几个条件任一不成立，就会出现<b>画面已经在放、回调却永远不来</b>的情况 ——
     * 上层于是以为黑屏，白白断掉一个本来正常工作的会话。
     * 像素采样是唯一不依赖 SDK 内部状态的判据。
     */
    public void onPixelsVisible() {
        if (surfaceReady) return;
        if (!castStarted) {
            // 还没开始投屏就采到"非黑"，只可能是上一场会话残留在 SurfaceView buffer 里的
            // 最后一帧（断开不会清空 buffer）。这种"陈旧画面"不算就绪。
            return;
        }
        if (System.currentTimeMillis() - tStartCast < 600L) {
            // 同理：刚 startCast 完的这 600ms 里，屏幕上仍是旧帧。留一个下限，
            // 让采样点一定落在"新会话已经开始灌数据"之后。
            return;
        }
        surfaceReady = true;
        final long now = System.currentTimeMillis();
        everSurfaceReady = true;
        tSurfaceReadyAt = now;
        cancelCastStallWatchdog();
        firstFrameFromStart = (tProgress100 == 0L);
        lastFirstFrameMs = firstFrameFromStart ? (now - tStartCast) : (now - tProgress100);
        main.removeCallbacks(surfaceWatchdog);
        stats.onFirstFrame();
        log("★ 像素采样确认画面已出 —— 距 startCast " + lastFirstFrameMs + "ms"
                + "（SDK onSurfaceReady 未到，属已知的判定链缺陷）");
        resolveTransportLabel();
        main.post(listener::onFrameVisible);
    }

    /** 当前通道的可读描述（投屏页角标 / 首页状态用）。 */
    private volatile String transportLabel = null;

    public String getTransportLabel() { return transportLabel; }

    /**
     * 判定并上报当前数据通道（0.3.7）。
     *
     * <p>USB 优先；无线走 P2P 时尝试反射拿组频段（WifiP2pGroup.getFrequency 是
     * hidden API），拿不到就只报 "P2P Wi-Fi"。
     */
    private void resolveTransportLabel() {
        String label;
        if (isUsbAttached()) {
            label = "USB AOA 有线";
        } else {
            label = "P2P Wi-Fi";
            try {
                android.net.wifi.p2p.WifiP2pManager wm = (android.net.wifi.p2p.WifiP2pManager)
                        appCtx.getSystemService(Context.WIFI_P2P_SERVICE);
                if (wm != null) {
                    android.net.wifi.p2p.WifiP2pManager.Channel ch =
                            wm.initialize(appCtx, main.getLooper(), null);
                    wm.requestGroupInfo(ch, group -> {
                        String l = "P2P Wi-Fi";
                        if (group != null) {
                            try {
                                Object f = group.getClass().getMethod("getFrequency").invoke(group);
                                int freq = (Integer) f;
                                if (freq >= 4900) {
                                    l = "P2P Wi-Fi 5GHz";
                                } else if (freq > 0) {
                                    l = "P2P Wi-Fi 2.4GHz";
                                }
                            } catch (Throwable ignored) {
                                // hidden API 被裁剪就退回无频段的描述
                            }
                        }
                        transportLabel = l;
                        log("[通道] 当前数据通道：" + l);
                        try { listener.onTransportLabel(l); } catch (Throwable ignored) { }
                    });
                    return;
                }
            } catch (Throwable ignored) {
                // P2P 服务不可用 → 保持 "P2P Wi-Fi"
            }
        }
        transportLabel = label;
        log("[通道] 当前数据通道：" + label);
        try { listener.onTransportLabel(label); } catch (Throwable ignored) { }
    }

    /**
     * 从后台回到前台。倒车影像这类系统浮层通常**不销毁** Surface，只是让 buffer 失效，
     * 回调一个都不来。此时必须主动重建一次投屏会话，否则画面停在被切走前的最后一帧。
     */
    public void rebindAfterResume() {
        if (adapter == null || !sdkReady) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            log("onResume：Surface 不可用，等 surfaceCreated 重建");
            return;
        }
        log("onResume：强制重建投屏会话");
        castStarted = false;
        surfaceReady = false;
        castRetry = 0;
        castRebuildCount = 0;
        // ★ 必须传 false。反编译 UCarAdapter.pauseCast(Z)：
        //      mCastManager.pauseCast();
        //      if (gotoBackground) mWorkerHandler.post(() -> mControlManager.sendGotoBackground());
        //   无参 pauseCast() 等价于 pauseCast(true)，会**通知手机「车机已切后台」**。
        //   手机收到后会把投屏应用退回后台甚至停编码 —— 我们要的只是换一次输出面，
        //   绝不能告诉手机"我退出了"。
        try {
            adapter.pauseCast(false);
        } catch (Throwable t) {
            log("pauseCast(false) 异常: " + t);
        }
        tryStartCast();
    }

    public void setSurface(Surface s, int w, int h) {
        if (s == null) return;

        // ★ 只有「同一个 Surface + 尺寸没变 + 已经在投」才算重复回调。
        //   原来的判断是 `s == this.surface` 就直接 return —— 但 surfaceCreated 与
        //   surfaceChanged 传进来的是同一个 Surface 对象，而 surfaceCreated 那一刻
        //   尺寸往往还是 0（日志里 surfaceChanged 1920x1080 要晚 2ms 才到）。
        //   结果 sink 被绑在一个尺寸未知的 Surface 上，首帧出不来：界面连上了、进度到
        //   100、startCast 也返回 true，但就是黑屏；退出重连时 Activity 已存在，Surface
        //   创建时就带正确尺寸，所以第二次正常。
        final boolean sameSurface = (s == this.surface);
        final boolean sameSize = (w == surfaceW && h == surfaceH);
        if (sameSurface && sameSize && castStarted) return;

        this.surface = s;
        this.surfaceW = w;
        this.surfaceH = h;
        castRetry = 0;          // 新 Surface 到位，重试预算重置

        if (castStarted) {
            log("Surface 变化（" + w + "x" + h + "）→ 重置 castStarted，重新 startCast");
            castStarted = false;
        }

        // 尺寸还没确定时不启动：等 surfaceChanged 带着真实尺寸再进来。
        if (w <= 0 || h <= 0) {
            log("Surface 尺寸未就绪（" + w + "x" + h + "），等 surfaceChanged");
            return;
        }
        tryStartCast();
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    /**
     * 构建标记。每次编版递增，启动时打一行 —— 这样从日志里就能确认
     * 「跑的是不是刚才那一版」，不用再靠某个字符串猜。
     */
    /**
     * 诊断用的构建标记。
     *
     * <p>以前是手写的时间戳（dilinkfix-2026-10-24.22），每次发版都要记得改，
     * 忘改就会出现「报告里的版本号和新包对不上」。改为直接取 Gradle 的
     * versionName：debug 变体会是 {@code 0.2.1-debug}，release 是 {@code 0.2.1}，
     * 永远与实际安装包一致。
     */
    public static final String BUILD_TAG = BuildConfig.VERSION_NAME;

    public void start() {
        log("构建标记 " + BUILD_TAG);
        log("=== 启动（ICCOA CarWith）===");
        // 每次开始新会话都重置触控日志预算，否则第一次调试用完 12 条之后，
        // 后续连接里的触摸将完全不留痕迹（曾经因此误判为「事件没到」）。
        touch.resetLogBudget();
        uibcFixBudget = 3;
        stats.resetForNewSession();
        musicLogBudget = 6;
        carLinkEnabled = IccoaPrefs.getBool(appCtx, IccoaPrefs.KEY_CAR_LINK, true);
        gpsUplinkEnabled = IccoaPrefs.getBool(appCtx, IccoaPrefs.KEY_GPS_UPLINK, true);
        usbPriority = IccoaPrefs.getBool(appCtx, IccoaPrefs.KEY_USB_PRIORITY, true);
        registerUsbReceiver();
        cancelEarlyBail();
        // 每次新会话重置首屏看门狗：重建次数按「单次连接」计，不能跨会话累计。
        castRebuildCount = 0;
        surfaceReady = false;
        main.removeCallbacks(surfaceWatchdog);

        // ★ UCarConfig 只在 init() 时生效一次，而 start() 在「已初始化」时会直接返回。
        //   于是用户改了 Network transport 之后，界面上变了、报告头也变了，但实际会话
        //   仍在用旧配置（典型症状：报告头写 transport=0，而"通道能力"那行还是 1）。
        //   这里比对当前设置与已生效的值，不一致就停掉旧会话并重建。
        final int currentTransport = readIntSetting(IccoaPrefs.KEY_TRANSPORT, 1);
        final boolean transportChanged =
                (appliedTransport != Integer.MIN_VALUE && currentTransport != appliedTransport);

        if (sdkInitialized && adapter != null && !transportChanged) {
            log("重启：SDK 已初始化，仅重新广播");
            reAdvertise();
            return;
        }

        if (sdkInitialized && transportChanged) {
            log("通道配置 " + appliedTransport + " → " + currentTransport + "，重建 SDK");
            try {
                adapter.stopAdvertise();
            } catch (Throwable t) {
                log("stopAdvertise 异常: " + t);
            }
            try {
                adapter.disconnect();
            } catch (Throwable t) {
                log("disconnect 异常: " + t);
            }
            sdkInitialized = false;
            sdkReady = false;
            castStarted = false;
            sessionActive = false;
            main.removeCallbacks(autoRefreshRunnable);
            main.removeCallbacks(reAdvertiseRunnable);
        }

        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        appliedTransport = currentTransport;

        if (adapter == null) {
            try {
                adapter = UCarAdapter.getInstance();
                adapter.registerCarConnectListener(connectListener);
                adapter.registerPhoneDataListener(phoneDataListener);
            } catch (Throwable t) {
                log("✗ getInstance/register 异常: " + t);
                return;
            }
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
                // SDK 是单例，重复 init() 未必被允许。切换通道后若走到这里，
                // 说明重建没成功，只能靠冷启动。
                if (appliedTransport != Integer.MIN_VALUE) {
                    log("→ 若刚切换过通道，请完全退出 App（最近任务划掉）后重开");
                }
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
        main.removeCallbacks(usbPriorityFallback);
        cancelAutoRefresh();
        unregisterUsbReceiver();
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
            // ★ pauseCast(false)：不向手机下发 "GotoBackground"。
            //   紧跟着就是 disconnect()，再让手机把投屏应用退回后台毫无意义，
            //   反而可能让手机进入「被切走」状态、推迟自动重连 —— 而重连正是
            //   冷启动首场之后恢复画面的唯一路径。
            try { adapter.pauseCast(false); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
        }
        sessionActive = false;
        castStarted = false;
        lastPinCode = null;
        // 本端主动断开不是丢链：撤销待发的僵死判定，避免 2 秒后把投屏页误杀
        linkLostArmed = false;
        main.removeCallbacks(linkLostRunnable);
        cancelCastStallWatchdog();
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    // ==================================================================
    // 广播 / 投屏
    // ==================================================================

    private void doAdvertise() {
        if (adapter == null) return;

        // USB 优先：线插着就不广播，把无线这条路先关掉（详见 usbReceiver 的注释）。
        //
        // ★ 必须带兜底：有些车机/线材只供电不跑 AOA 数据，这时如果不广播，手机永远
        //   发现不了车机，界面上连配对码都不会出现。所以 10 秒内没建链就退回广播。
        if (usbPriority && isUsbAttached()) {
            log("USB 优先：检测到 USB 配件，跳过蓝牙广播，只等 AOA 建链（10 秒无果则回退）");
            main.removeCallbacks(usbPriorityFallback);
            main.postDelayed(usbPriorityFallback, USB_PRIORITY_FALLBACK_MS);
            tryStartCast();
            return;
        }

        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        if (ok) {
            // ★ 预热必须排在广播**之后**。
            //   startCast 与 startAdvertise 都排在 SDK 同一个 worker 线程上；如果先把
            //   MediaCodec 的 configure 丢进去而它在某些机型上卡住，广播就永远轮不到执行，
            //   表现出来就是"六位配对码不出来"。
            schedulePinWatchdog();
            // ★ 关键时序修正：把「建解码器」提前到**手机连上之前**。
            //
            //   逐场对比日志得到的规律，冷启动首场必然失败、第二场必然成功：
            //     失败场：startCast 在 progress 10~30（手机已经连上、正在协商）
            //     成功场：startCast 在 progress 0（手机还没连上，解码器已就位）
            //   差别在于 native sink 已经开推之后才建 VideoPlayerV2 —— 开推瞬间那个
            //   关键帧没有解码器接，之后手机不再补发 IDR，画面就一直黑到会话结束。
            //
            //   所以在广播成功、手机尚未连入的这个窗口，先用同一尺寸的离屏 Surface 把
            //   VideoPlayerV2 建起来（configure + start 全部落定）。手机连上时它已经在跑，
            //   后面真实 Surface 到达只需 setOutputSurface。
            //
            //   只在「还没空配过」时做一次：VideoPlayerV2 跨会话复用，mIsConfigured 一旦
            //   为 true 就永久为 true，重复空配只会把输出面来回切，反而多一次黑屏。
            if (!handoffPrimed) {
                main.postDelayed(this::primeDecoderOffscreen, 600L);
            }
        }
        tryStartCast();
    }

    /**
     * 配对码看门狗：广播成功后 8 秒还没拿到 onPinCode，就重播一次并留日志。
     * 目的不是修复，而是让"没有配对码"这件事在日志里立刻可见，而不是全靠肉眼。
     */
    private int pinRetryCount = 0;

    private void schedulePinWatchdog() {
        main.removeCallbacks(pinWatchdog);
        pinRetryCount = 0;
        main.postDelayed(pinWatchdog, 8000L);
    }

    private final Runnable pinWatchdog = new Runnable() {
        @Override
        public void run() {
            if (lastPinCode != null) return;      // 已经有码，正常
            if (pinRetryCount >= 2) {
                log("✗ 8s 内两次重播都没有配对码，停止重试（检查蓝牙广播能力）");
                return;
            }
            pinRetryCount++;
            log("✗ 8s 未收到配对码 → 重新广播（第 " + pinRetryCount + " 次）");
            try {
                if (adapter != null) adapter.stopAdvertise();
            } catch (Throwable ignored) {
            }
            main.postDelayed(() -> {
                if (adapter != null) {
                    boolean ok = adapter.startAdvertise();
                    log((ok ? "✓ " : "✗ ") + "重播 startAdvertise() = " + ok);
                }
                main.postDelayed(pinWatchdog, 8000L);
            }, 500L);
        }
    };

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

        // ★ 首次投屏：先用「同尺寸离屏 Surface」把 configure 消耗掉，再交接真实 Surface。
        //   目的见 handoffPrimed 的注释：让首场也走 setOutputSurface，而不是第一次 configure。
        if (!handoffPrimed) {
            // ★ 0.3.11：空配真正成功才置 handoffPrimed。旧写法先进 true 再试，
            //   primeDecoderOffscreen 失败（如 adapter 未就绪）也不回滚 —— 本进程
            //   后续所有会话都再也补不上空配，首场黑问题被永久保留。
            if (primeDecoderOffscreen()) {
                handoffPrimed = true;
                // 空配刚开始 → 等它落定再交接；空配早已完成（本轮常见，Surface 比手机晚到）
                // → 立即交接。日志里这一步白等了 300ms，正好把真实 Surface 的 startCast
                // 推到 progress 30，而 progress 0 才是唯一稳定成功的时机。
                final long elapsed = System.currentTimeMillis() - primeStartedAt;
                final long wait = elapsed >= PRIME_HANDOFF_DELAY_MS
                        ? 0L : PRIME_HANDOFF_DELAY_MS - elapsed;
                if (wait > 0L) {
                    main.postDelayed(this::castToRealSurface, wait);
                } else {
                    castToRealSurface();
                }
                return;
            }
        }
        castToRealSurface();
    }

    /** 真正把画面投到车机屏幕的 Surface 上。 */
    private void castToRealSurface() {
        if (!sdkReady || adapter == null || castStarted) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            log("交接真实 Surface 时它已失效，等下一次 surfaceChanged");
            primingPhase = false;
            // 交接没完成 → 允许下一次重新空配，否则会被永久跳过。
            handoffPrimed = false;
            return;
        }
        // ★ 防抢跑：日志里空配刚发出 56ms，第二次 surfaceChanged 就把交接提前触发了。
        //   此时 SDK 的 configure() 还没跑完（它内部是 post 到 worker handler 异步执行的），
        //   真实 Surface 的 startCast 会再次走 configure 分支 —— 空配等于白做，首场照旧黑。
        final long elapsed = System.currentTimeMillis() - primeStartedAt;
        if (primeStartedAt > 0L && elapsed < PRIME_HANDOFF_DELAY_MS) {
            final long wait = PRIME_HANDOFF_DELAY_MS - elapsed;
            log("交接被提前触发（" + elapsed + "ms < " + PRIME_HANDOFF_DELAY_MS
                    + "ms），推迟 " + wait + "ms，等空配的 configure 落定");
            main.postDelayed(this::castToRealSurface, wait);
            return;
        }
        primingPhase = false;
        surfaceReady = false;
        tStartCast = System.currentTimeMillis();
        stats.onCastStart();
        stats.fps = appliedFps;
        // ★ 用带 IAppSurfaceStateListener 的重载：startCast 返回 true 只代表「调用被接收」，
        //   真正决定有没有画面的是 SDK 后续的 onSurfaceReady()。冷启动第一次连接时，
        //   解码器/native sink 首次初始化较慢，startCast 返回 true 但迟迟不回调
        //   onSurfaceReady，表现就是「连上了、进度 100、但黑屏」；退出重连时
        //   native 已热，回调立刻到达，所以第二次正常。
        boolean ok;
        try {
            ok = adapter.startCast(s, videoW, videoH, surfaceStateListener);
        } catch (Throwable t) {
            log("startCast(4 参) 异常，退回 3 参: " + t);
            ok = adapter.startCast(s, videoW, videoH);
        }
        castStarted = ok;
        if (ok) {
            startGpsTick();
            scheduleCastStallWatchdog();   // ★ 0.3.11：起投后 12s 无画面的兜底
            // USB（AOA）会话下 onWfdSinkReady 不触发，source size 停在 0，
            // 触摸会被 SDK 全拒；这里主动补一次（无线会话下补成 display 尺寸
            // 同样无害，且能消除手机上报源尺寸与画布不一致造成的错位）。
            ensureUibcSourceSize("startCast");
        }
        log((ok ? "✓ " : "✗ ") + "startCast(" + videoW + "x" + videoH + ") = " + ok
                + " surfaceValid=" + s.isValid()
                + " panel=" + screenW + "x" + screenH
                + " progress=" + lastProgress
                + (lastProgress < 40 ? "（预热：早于媒体协商）" : ""));
        if (ok) {
            main.post(listener::onCastStarted);
            // 交接完成 → 延后回收空配用的离屏 Surface。留 2 秒是为了避开
            // MediaCodec 内部切换输出面的过渡窗口（setOutputSurface 之后仍可能有
            // 一两帧走向旧 Surface）。
            // 只在「确实已经切到真实 Surface」时才回收：若交接其实没成功（比如
            // setOutputSurface 抛了异常被 SDK 吞掉），这块离屏 Surface 仍是解码器
            // 当前的输出面，提前 release 会让整条视频链路直接报错。
            if (primeSurface != null) {
                main.postDelayed(() -> {
                    if (castStarted && !primingPhase) releasePrime();
                    else log("空配 Surface 保留（交接未确认）");
                }, 2000L);
            }
        }
    }

    /** SDK 认为 Surface 可以收画面了才回调；这是唯一可靠的「首屏是否真正就绪」信号。 */
    private final IAppSurfaceStateListener surfaceStateListener =
            new IAppSurfaceStateListener() {
                @Override
                public void onSurfaceReady() {
                    // 空配阶段的 Surface 在离屏，它"ready"跟车机屏幕无关，不能当首帧。
                    if (primingPhase) {
                        log("（空配阶段 onSurfaceReady，忽略）");
                        return;
                    }
                    final long now = System.currentTimeMillis();
                    surfaceReady = true;
                    everSurfaceReady = true;
                    tSurfaceReadyAt = now;
                    cancelCastStallWatchdog();
                    firstFrameFromStart = (tProgress100 == 0L);
                    lastFirstFrameMs = firstFrameFromStart
                            ? (now - tStartCast)
                            : (now - tProgress100);
                    main.post(listener::onFirstFrame);
                    log("★ onSurfaceReady() —— 首帧"
                            + (firstFrameFromStart ? "（早于 progress100）" : "")
                            + " 距 progress100 "
                            + (tProgress100 == 0L ? "n/a" : (now - tProgress100) + "ms")
                            + "，距 startCast " + (now - tStartCast) + "ms"
                            + "，当时进度 " + lastProgress);
                    // 0.3.8 修复：首帧也可能从 SDK onSurfaceReady 这条路进来，
                    // 此时 onPixelsVisible 会因 surfaceReady 已置位而提前返回，
                    // 导致通道角标永远不触发。两条首帧路径都必须判定通道。
                    resolveTransportLabel();
                }
            };

    /**
     * 首屏看门狗的判定基准，从 1 秒放宽到 4.5 秒。
     *
     * <p>原来的 1 秒是「onSurfaceReady 迟到就判死」，但像素采样（{@link #onPixelsVisible()}）
     * 才是真判据，而像素采样本身有 250ms 的采样周期、还要等解码器出第一个 buffer。
     * 日志里 progress 100 之后 1.2~2.0 秒才出首帧的样本占多数，1 秒等于直接误杀。
     */
    private static final long SURFACE_READY_TIMEOUT_MS = 4500L;

    /**
     * 冷启动首场（本进程还没出过画面）的等待上限。
     *
     * <p>取 900ms，且**从 progress 90 起算**而不是 100。
     *
     * <p>依据是实测：健康会话的像素都在 progress 90 之后 206~875ms 内到达；而
     * progress 90→100 这段协商本身还要 1.8~1.9 秒。等 progress 100 再从零计时，
     * 等于白白多花这 1.9 秒 —— .18/.19 两轮日志里那 1.2 秒等待其实全落在
     * 「协商已经走完、画面却不会来」的空档上。
     */
    private static final long COLD_START_TIMEOUT_MS = 1300L;

    /**
     * USB 链路的冷启动等待。
     *
     * <p>USB（AOA）首连也慢，但性质不同：它是「慢，最后能出画面」，不是「这一场必然不出」。
     * 实测里无线首场是零像素、必须重连；USB 只是首帧晚。所以这里不给它用 900ms 的快速判死，
     * 也不做秒断 —— 那会把一条本来会成功的会话掐掉。
     */
    private static final long COLD_START_TIMEOUT_USB_MS = 6000L;

    private void scheduleSurfaceWatchdog() {
        main.removeCallbacks(surfaceWatchdog);
        final long timeout;
        if (everSurfaceReady) {
            timeout = SURFACE_READY_TIMEOUT_MS;
        } else if (isUsbAttached()) {
            timeout = COLD_START_TIMEOUT_USB_MS;
        } else {
            timeout = COLD_START_TIMEOUT_MS;
        }
        main.postDelayed(surfaceWatchdog, timeout);
    }

    /**
     * 首屏看门狗。
     *
     * <p>判定依据改成「像素是否出过」而不是「onSurfaceReady 是否到过」—— 后者在
     * {@code mLastReadyCountTime} 等内部状态不满足时会永久缺失，见
     * {@link #onPixelsVisible()} 的注释。
     *
     * <p>处置顺序也改了：先做**不拆会话**的重绑（{@code pauseCast(false)} + 重新 startCast），
     * 这一步只换输出面，手机端毫无感知；两步都不行才断会话重播。
     */
    private final Runnable surfaceWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!castStarted || surfaceReady) return;

            // ★ 冷启动第一场（本进程还没出过画面）：实测这一场手机压根不推视频流。
            //   证据：第 1 场 4500ms 无像素 → 重绑 → 又 4500ms 仍无；
            //   而重连后的第 2 场启动时 mIsFirstLaunch 仍为 true，说明第 1 场
            //   连一个关键帧都没送到解码器。这一场无论换几次输出面都没用。
            //   所以不再浪费 4.5 秒去做注定无效的重绑，直接快速重连，
            //   把黑屏/等待时间压到最短。
            if (!everSurfaceReady) {
                // 注意这里用 COLD_START_TIMEOUT_MS 而不是 SURFACE_READY_TIMEOUT_MS：
                // .18 的日志里文案打的是 4500，实际只等了 1200，对不上账。
                log("✗ " + COLD_START_TIMEOUT_MS + "ms 无像素（本进程首场，手机未推流）"
                        + " → 直接断会话，+" + COLD_START_RE_ADVERTISE_DELAY_MS + "ms 重播");
                main.post(listener::onRecovering);
                stats.reconnectCount++;
                try {
                    disconnectSession();
                } catch (Throwable t) {
                    log("disconnectSession 异常: " + t);
                }
                scheduleReAdvertise(COLD_START_RE_ADVERTISE_DELAY_MS);
                return;
            }

            // 出过画面之后的中途失效：先重绑输出面（不拆会话，手机无感知），
            // 仍不恢复再断会话。
            if (castRebuildCount == 0) {
                castRebuildCount++;
                log("✗ " + SURFACE_READY_TIMEOUT_MS + "ms 无像素 → 重绑输出面（不动会话）");
                try {
                    adapter.pauseCast(false);
                } catch (Throwable t) {
                    log("pauseCast(false) 异常: " + t);
                }
                // 重绑会让 SDK 走 setOutputSurface 分支，同时重置 mLastReadyCountTime，
                // 这正是「第二场一律正常」时它经历的状态。
                castStarted = false;
                tryStartCast();
                main.removeCallbacks(surfaceWatchdog);
                main.postDelayed(surfaceWatchdog, SURFACE_READY_TIMEOUT_MS);
                return;
            }

            log("✗ 重绑无效 → 断开会话并保持广播，等待手机重连");
            main.post(listener::onRecovering);
            try {
                disconnectSession();
            } catch (Throwable t) {
                log("disconnectSession 异常: " + t);
            }
        }
    };

    /**
     * 起投兜底看门狗（0.3.11）。首屏看门狗只在 progress 90/100 时排程，秒断只认
     * progress 10~20 —— 若协商卡在 30~80，起投后没有任何计时器，黑屏可以无限持续。
     * 这里的职责简单粗暴：startCast 成功后 12 秒内没确认过画面，就断会话重播。
     */
    private static final long CAST_STALL_TIMEOUT_MS = 12_000L;

    private final Runnable castStallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!castStarted || surfaceReady) return;
            log("✗ 起投 " + (CAST_STALL_TIMEOUT_MS / 1000) + "s 仍无画面"
                    + "（协商可能卡在中途，lastProgress=" + lastProgress + "）→ 断会话重播");
            main.post(listener::onRecovering);
            stats.reconnectCount++;
            try {
                disconnectSession();
            } catch (Throwable t) {
                log("disconnectSession 异常: " + t);
            }
            scheduleReAdvertise(COLD_START_RE_ADVERTISE_DELAY_MS);
        }
    };

    private void scheduleCastStallWatchdog() {
        main.removeCallbacks(castStallWatchdog);
        main.postDelayed(castStallWatchdog, CAST_STALL_TIMEOUT_MS);
    }

    private void cancelCastStallWatchdog() {
        main.removeCallbacks(castStallWatchdog);
    }

    /** 冷启动首场失败后重新广播的延时。手机上 Jovi InCar 收到广播到重连约 1~2 秒。 */
    private static final long COLD_START_RE_ADVERTISE_DELAY_MS = 800L;

    /**
     * 冷启动首场「秒断」：确认这一场注定失败之后，多等一秒都是纯浪费。
     *
     * <p>依据：五轮日志里，进程启动后的第一场会话从 progress 10 到 90 要 1.7 秒，
     * 90 之后再等 900ms 才判死 —— 加起来 2.6 秒，而这一场从来不会出画面。
     * 现在改成「一看到 progress 10（手机已连上）就排一次主动重连」，
     * 把这段等待直接砍掉。
     *
     * <p>关掉它（改 false）就退回 .20 的行为：等 progress 90 + 900ms 再判死。
     */
    private static final boolean EARLY_BAIL_ENABLED = true;
    /** 从 progress 10 到主动断开之间的缓冲。留一点时间让手机把会话建完，否则它可能不认这次连接。 */
    private static final long EARLY_BAIL_MS = 700L;

    /** 本轮是否已经排过「秒断」，避免 progress 反复回调排多次。 */
    private boolean earlyBailArmed = false;

    private final Runnable earlyBailRunnable = new Runnable() {
        @Override
        public void run() {
            if (surfaceReady || everSurfaceReady || !castStarted) {
                return;
            }
            log("✗ 首场秒断：progress 10 后 " + EARLY_BAIL_MS + "ms 主动重连"
                    + "（本进程尚未出过画面，这一场手机不会推流）");
            stats.reconnectCount++;
            try {
                disconnectSession();
            } catch (Throwable t) {
                log("earlyBail disconnect 异常: " + t);
            }
            scheduleReAdvertise(COLD_START_RE_ADVERTISE_DELAY_MS);
        }
    };

    /**
     * 是否挂着 USB 配件。
     *
     * <p>用于把「无线首场必黑」的快速判死限制在无线链路上：USB 走 AOA，首场是正常的，
     * 提前掐断反而会把好会话杀掉。
     */
    /**
     * USB 线是否插着（0.3.7 修复）。
     *
     * <p>原实现用 {@code UsbManager.getAccessoryList()}，它只返回已进入 AOA accessory
     * 模式的设备。而手机插上车机时以 MTP 模式枚举（UsbDevice，如 vivo VID 2D95 /
     * PID 6012），AOA 模式要等手机端切换——所以 accessory 列表**恒为空**，
     * USB 优先从来没生效过（实测报告里 0 条「USB 优先」日志）。
     *
     * <p>现在改为检查 UsbDevice 列表：有设备（即手机）插着就算，无论它当前处于
     * 哪种 USB 模式。accessory 模式也保留检查，兼容真 AOA 场景。
     */
    private boolean isUsbAttached() {
        try {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
            if (um == null) {
                return false;
            }
            android.hardware.usb.UsbAccessory[] acc = um.getAccessoryList();
            if (acc != null && acc.length > 0) {
                return true;
            }
            java.util.HashMap<String, android.hardware.usb.UsbDevice> devs = um.getDeviceList();
            return devs != null && !devs.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void cancelEarlyBail() {
        main.removeCallbacks(earlyBailRunnable);
        earlyBailArmed = false;
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            main.removeCallbacks(pinWatchdog);
            pinRetryCount = 0;
            log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName);
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        @Override
        public void onConnectStateChanged(String deviceID, int oldState, int newState) {
            if (deviceID != null && !"-1".equals(deviceID) && !deviceID.isEmpty()) {
                lastDeviceId = deviceID;
            }
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
                main.removeCallbacks(usbPriorityFallback);
                // ★ 协商中：也要置 sessionActive，否则 30 秒看门狗会在鉴权进行到一半时
                //   stop→start 重播，把正在跑的会话连根拔掉（实测 vivo 上就是卡在这一步）。
                //   改用更长的 CONNECTING_TIMEOUT 兜底，超时才放开。
                sessionActive = true;
                main.removeCallbacks(reAdvertiseRunnable);
                // 新会话开始，首屏看门狗的重建次数必须归零 —— 否则上一轮断开前的
                // 计数会让这一轮刚开始就直接跳到最后一步。
                castRebuildCount = 0;
                surfaceReady = false;
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
                String hint = (appliedTransport == 1)
                        ? "（SoftAP 模式：车机系统热点必须处于开启状态 —— SDK 拿不到 TETHER_PRIVILEGED，"
                          + "无法自己开热点；如果热点本来就没开，请改用 WiFi Direct）"
                        : "（P2P 模式：检查车机 Wi-Fi 是否被其它连接占用、手机端是否允许 Wi-Fi Direct）";
                log("✗ 连接失败码 " + newState + " → 交回看门狗，准备重播 " + hint);
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
            // ★ 僵死画面防护（0.3.7，0.3.8 放宽）。实测真实断联时 deviceID 往往仍是
            //   有效值（dev=099b29），只有状态机掉回 STATE_-1 —— 原来的
            //   "-1".equals(deviceID) 条件导致防护从不触发，车机停在最后一帧。
            //   现改为：只要投屏已开始、状态掉回 -1（无论标识是否还在），就开 2 秒
            //   确认窗；窗口内手机重连/协商推进（state 1..3 或 connecting progress>0）
            //   即撤销。本端主动断开不受影响（disconnectSession 先清 castStarted）。
            // ★ 僵死画面防护（0.3.10 修正判据）。SDK 在 progress 100 之后必然回调一次
            //   CONNECTED -> STATE_-1 的过渡值（实测每个正常会话都有，首帧都出了它也来），
            //   且此时 progress 已到顶、不会再有事件来撤销 —— 0.3.9 在这里 2 秒后必误杀，
            //   造成「连上 2 秒 → 退出 → 手机还在 → 自动重连 → 再杀」的无限循环。
            //   判据：距 progress 100 不足 3 秒的 -1 一律视为过渡值跳过；稳态期的 -1
            //   （真丢链）才武装 2 秒确认窗。
            long sinceP100 = tProgress100 == 0L ? Long.MAX_VALUE
                    : System.currentTimeMillis() - tProgress100;
            if (castStarted && everSurfaceReady && newState == -1 && !linkLostArmed) {
                if (sinceP100 < 3000L) {
                    log("· CONNECTED 后的过渡 STATE_-1（距 progress100 " + sinceP100 + "ms），跳过僵死判定");
                } else {
                    linkLostArmed = true;
                    log("· 稳态期连接状态掉回 STATE_-1，2 秒内未恢复则退出投屏页（僵死防护）");
                    main.postDelayed(linkLostRunnable, 2000L);
                }
            } else if (newState >= 1 && newState <= 3) {
                // 会话恢复（手机重连/协商继续），撤销待发的丢链判定
                linkLostArmed = false;
                main.removeCallbacks(linkLostRunnable);
            }
            final int st = newState;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            log("onConnectingProgress " + progress);
            main.post(() -> listener.onProgress(progress));
            // 僵死防护撤销（0.3.8）：真实断联后 SDK 常在 2 秒内重启协商（progress 10 起步），
            // 只要协商还在推进就说明链路活着，撤掉待发的退出判定。
            if (linkLostArmed && progress > 0) {
                linkLostArmed = false;
                main.removeCallbacks(linkLostRunnable);
                log("· 协商仍在推进（进度 " + progress + "%），撤销退出投屏页判定");
            }
            // ★ 协商到 100% 才意味着「会话建完了，接下来应该出画面」。
            //   实测 onSurfaceReady 通常在 100% 之后 1~3 秒到达；从这一刻开始计时，
            //   才不会在 progress 90→100 这段本来就慢的协商里误判。
            final long now = System.currentTimeMillis();
            // 新会话的首个进度事件有时是 10、有时是 20（取决于手机先报哪一步），
            // 所以用区间判断；0 不在此列 —— 那是断开，不该把本轮结果清掉。
            if (progress > 0 && progress <= 20 && tProgress100 > 0L) {
                // 新一轮会话，清掉上一轮的状态，避免汇总里串数据
                tProgress90 = 0L;
                tProgress100 = 0L;
                tSurfaceReadyAt = 0L;
                lastSegMs = -1L;
                lastFirstFrameMs = -1L;
                firstFrameFromStart = false;
                surfaceReady = false;   // 否则上一轮成功会污染这一轮的「结果=正常/黑屏」
                castRetry = 0;
                connectTypeLogged = false;
                log("── 新一轮会话开始，诊断计时清零");
            }
            lastProgress = progress;

            // ★ 秒断：本进程还没出过画面时，这一场在 progress 10 就已经注定失败。
            //
            //   ★★ 但**只在无线链路上**做。USB（AOA）是另一条通路，实测首场就能出画面，
            //   在 700ms 处掐断会把一条本来正常的会话杀掉 —— 所以只要挂着 USB 配件就不秒断。
            // ★ 0.3.11 两个改动：
            //   ① 触发窗口 10 → 10..20。实测首个进度事件有时是 10 有时是 20，
            //      只认 ==10 的场次完全跳过秒断。
            //   ② 空配成功（handoffPrimed）时不再秒断。秒断的前提「首场解码器没预热、
            //      手机必不推流」只在旧版本成立；离屏空配后首场完全可能出画面，
            //      700ms 掐死它就是「重建 3 轮才连上」的直接原因。交回冷启动
            //      看门狗（progress 90 + 1300ms）按像素判据裁决。
            if (EARLY_BAIL_ENABLED && progress >= 10 && progress <= 20 && !handoffPrimed
                    && !everSurfaceReady && !earlyBailArmed && castStarted) {
                if (isUsbAttached()) {
                    // USB 首连也是慢，但「慢」不等于「不出」：实测它能出画面，只是首帧晚。
                    // 掐断会给手机一次额外的 AOA 重协商，反而更慢，所以这里只放宽看门狗。
                    earlyBailArmed = true;
                    log("· 首场走 USB（AOA）：不做秒断，把首帧等待放宽到 "
                            + COLD_START_TIMEOUT_USB_MS + "ms");
                } else {
                    earlyBailArmed = true;
                    main.post(listener::onRecovering);
                    main.postDelayed(earlyBailRunnable, EARLY_BAIL_MS);
                    log("· 首场走无线：" + EARLY_BAIL_MS + "ms 后主动重连");
                }
            }

            if (progress == 90) {
                tProgress90 = now;
                stats.onProgress(progress);
                // ★ 冷启动首场：从 progress 90 就开始计时。
                //   90 之后手机才真正开始推流，健康会话的像素在 206~875ms 内到达；
                //   而 90→100 那段协商还要 1.8 秒。等 100 再计时，等于白等这段。
                if (!everSurfaceReady) {
                    scheduleSurfaceWatchdog();
                }
            }
            if (progress >= 100 && tProgress100 == 0L) {
                tProgress100 = now;
                stats.onProgress(progress);
                lastSegMs = (tProgress90 > 0L) ? (now - tProgress90) : -1L;
                log("[诊断] 协商末段 90→100 = " + lastSegMs + "ms"
                        + "（startCast 至今 " + (now - tStartCast) + "ms）");
                logConnectType(phoneId);
                scheduleSurfaceWatchdog();
            }
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

        @Override
        public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) {
            // 手机端曲目/播放状态。这里只做两件事：留一行日志、往上抛给 UI。
            // 具体渲染（车机侧封面、进度条）留给后续版本，SDK 已经把数据送来了。
            final String text = IccoaCarLink.describe(info);
            if (musicLogBudget-- > 0) {
                log("♪ " + text);
            }
            main.post(() -> listener.onMusicInfo(text));
        }

        @Override
        public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) {
            // CallInfo 只在状态切换时来，量很小，全部记下来。
            log("☎ callInfo " + (info == null ? "null" : info.toString()));
        }

        @Override
        public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) {
            final String text = IccoaCarLink.describe(info);
            log("☎ " + text);
            main.post(() -> listener.onCallState(text));
        }
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
        int fps = clamp(readIntSetting(IccoaPrefs.KEY_FPS_LIMIT, 60), 15, 60);
        appliedFps = fps;
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
            int transport = readIntSetting(IccoaPrefs.KEY_TRANSPORT, 1);
            useP2p = (transport == 0);
            useAp = !useP2p;
            log("通道能力: transport=" + transport + " → P2P=" + useP2p + " SoftAP=" + useAp);
        } catch (Throwable t) {
            log("读取 transport 失败，退回 both: " + t);
        }

        UCarConfig.Builder b = new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(useP2p)
                .setSupportSoftAP(useAp);

        // ★ 只在走 P2P 时才设置 5GHz 信道表。
        //   这里原来写的是 `useP2p ? {...} : new int[0]`，而在 SoftAP 模式下传空数组，
        //   SDK 会抛 java.security.InvalidParameterException: channels can't be null，
        //   整个 buildConfig() 失败 → start() 直接 return → 既不 init 也不广播 →
        //   界面上连六位配对码都不会出现。
        //   车机上 transport=0（P2P）所以一直没暴露；换成 Hotspot 的假车机立刻必现。
        if (useP2p) {
            //   UNII-1(36-48) + UNII-3(149-161)，全部避开 DFS（雷达检测会瞬断）。
            b.setAllowed5gChannels(new int[]{36, 40, 44, 48, 149, 153, 157, 161});
        }

        b.setSupportCamera(false);
        b.setSupportHevc(false);
        b.setSupportMic(true);
        // ★ 低延迟解码模式（真实生效）。这个开关有两层作用：
        //   ① 反编译 lambda$startCast$25：它作为 Z 参数传给 CastManager.startCast
        //      → VideoPlayerV2.setFlagWfdPerf()，影响解码/渲染的排队策略；
        //   ② 它随 GetUCarConfigResponse 上报给手机，手机端据此选低延迟编码档
        //      （通常意味着更短的 GOP），首帧更近、丢包恢复更快。
        //   注意：下面不能再出现 setSupportLowLatencyDecodingMode(false) ——
        //   之前链式写法里重复了一次 false，把这里的 true 覆盖掉了。
        b.setSupportLowLatencyDecodingMode(true);
        b.setUseCustomAudioRecord(false);
        b.setSupportStereoRecord(false);
        b.setSupportPOINav(false);
        b.setCcdFilePath("ccd.data");
        b.setSupportedWorkModes(workMode);
        return b.build();
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
    /**
     * 触控回传（UIBC）。
     *
     * <p>映射与节流都在 {@link IccoaTouch} 里，这里只负责调用 SDK 和记日志。
     * 坐标基准是**视频画布**（{@code videoW} × {@code videoH}），不是面板；
     * 具体怎么映射由设置项 {@code touch-fit-mode} 决定（拉伸 / 等比）。
     */
    public void sendTouch(android.view.MotionEvent e, int viewW, int viewH) {
        if (adapter == null || e == null) {
            return;
        }
        final int action = e.getActionMasked();
        final long now = android.os.SystemClock.uptimeMillis();
        if (touch.shouldDrop(action, now)) {
            return;
        }

        final boolean fit = IccoaPrefs.getInt(appCtx, IccoaPrefs.KEY_TOUCH_FIT, 0) == 1;
        final int count = touch.map(e, viewW, viewH, videoW, videoH, fit);
        if (count <= 0) {
            return;
        }

        try {
            boolean ok = adapter.sendTouchEvent(action, count,
                    touch.idsTrimmed(), touch.xsTrimmed(), touch.ysTrimmed());
            // ★ 成功也要记一条。只在失败时打日志的话，「触摸没反应」时无从判断
            //   到底是事件没到、还是到了但被 SDK 拒、还是送到了手机但手机没执行。
            if (touch.takeLogSlot()) {
                log("触控回传 action=" + action + " count=" + count
                        + " view=" + viewW + "x" + viewH
                        + " video=" + videoW + "x" + videoH
                        + (fit ? " 等比" : " 拉伸")
                        + " p0=(" + touch.xs()[0] + "," + touch.ys()[0] + ")"
                        + " → " + (ok ? "已发送" : "被拒"));
            }
            if (!ok && uibcFixBudget > 0) {
                // 被拒 = SDK 的前置检查没过（encryption / source size，反编译见
                // docs §8）。先打一份内部状态快照，再懒补一次并重发这个事件。
                // 预算制：每次会话最多补 3 次，不会每次触摸都反射。
                uibcFixBudget--;
                log("[UIBC] 被拒快照 " + IccoaUibcFix.snapshot(adapter));
                ensureUibcSourceSize("touch-rejected");
                boolean retry = adapter.sendTouchEvent(action, count,
                        touch.idsTrimmed(), touch.xsTrimmed(), touch.ysTrimmed());
                log("[UIBC] 懒补后重发 → " + (retry ? "已发送" : "仍被拒")
                        + " | " + IccoaUibcFix.snapshot(adapter));
            }
        } catch (Throwable t) {
            if (touch.takeLogSlot()) {
                log("sendTouchEvent 异常: " + t);
            }
        }
    }

    /**
     * 把 SDK 的 UIBC source size 反射补成 display 尺寸（videoW × videoH）。
     *
     * <p>两种触发场景：
     * <ul>
     *   <li>USB（AOA）会话：{@code onWfdSinkReady} 不触发，source 停在 0，
     *       不补则每一刀都被拒；</li>
     *   <li>无线会话：手机上报的源尺寸若与视频画布不一致，坐标会按
     *       {@code x * displayW / sourceW} 错位，补成相等即恢复 1:1。</li>
     * </ul>
     * 全部 try-catch：反射失败只损失懒补与诊断，不影响投屏。
     */
    private void ensureUibcSourceSize(String why) {
        try {
            boolean ok = IccoaUibcFix.ensureSourceSize(adapter, videoW, videoH);
            log("[UIBC] 懒补 source → " + videoW + "x" + videoH
                    + " (" + why + ") = " + (ok ? "已执行" : "反射不可用"));
            log("[UIBC] 快照 " + IccoaUibcFix.snapshot(adapter));
        } catch (Throwable t) {
            log("[UIBC] 懒补异常: " + t);
        }
    }

    // ==================================================================
    // P2：车机 -> 手机 的能力（方控 / 日夜 / 电话 / 车况）
    // ==================================================================
    // 这些能力 SDK 早就提供了，只是上游从未调用过（见 docs/ICCOA-RUNTIME-FINDINGS.md §12）。
    // 统一规则：只有「真的在投屏」时才发，避免对着一个不存在的会话空发指令。

    /**
     * 一行把「这一版到底开了什么」写进日志。
     *
     * <p>以前排查要翻设置页才知道用户开了哪些开关、跑的是哪条通道；现在报告开头就有。
     * 装错包、开关没生效、USB 配件有没有插，都能一眼看出来。
     */
    private void logCapabilities() {
        final int transport = readIntSetting(IccoaPrefs.KEY_TRANSPORT, 1);
        final int fps = clamp(readIntSetting(IccoaPrefs.KEY_FPS_LIMIT, 60), 15, 60);
        final int fit = IccoaPrefs.getInt(appCtx, IccoaPrefs.KEY_TOUCH_FIT, 0);
        log("[能力] 通道=" + (transport == 0 ? "WiFi Direct(P2P)" : "车机热点(SoftAP)")
                + " 视频=" + videoW + "x" + videoH
                + " fps=" + fps
                + " | 车联=" + (carLinkEnabled ? "开" : "关")
                + " GPS回传=" + (gpsUplinkEnabled ? "开" : "关")
                + " 触控=" + (fit == 1 ? "等比" : "拉伸")
                + " 秒断=" + (EARLY_BAIL_ENABLED ? "开" : "关")
                + " USB优先=" + (usbPriority ? "开" : "关")
                + " | USB配件=" + (isUsbAttached() ? "已连接" : "未连接"));
        // UIBC 内部状态快照（反射读 SDK 的 UibcManager）：source=0x0 就是
        // 「触摸必然全被拒」的直接证据，省得下次再从行为反推。
        log("[能力] UIBC " + IccoaUibcFix.snapshot(adapter));
    }

    /** P2 能力是否启用（设置项 iccoa-carlink）。 */
    public boolean isCarLinkEnabled() {
        return carLinkEnabled;
    }

    /**
     * 方向盘 / 面板按键透传给手机。
     *
     * @return 是否真的发出去了（键码无法映射、没有会话时返回 false，由调用方决定是否本地处理）
     */
    public boolean sendKey(int keyCode, int action, int repeatCount) {
        if (!carLinkEnabled) {
            return false;
        }
        if (adapter == null || !castStarted) {
            // 没会话时按键交给本地 —— 这时按返回/主页应该照常工作，不能吞掉。
            if (touch.takeLogSlot()) {
                log("方控 ← " + KeyEvent.keyCodeToString(keyCode) + "（当前没在投屏，交本地处理）");
            }
            return false;
        }
        final boolean ok = IccoaCarLink.sendKey(adapter, keyCode, action, repeatCount);
        if (touch.takeLogSlot()) {
            log("方控 " + (ok ? "→" : "✗") + " " + KeyEvent.keyCodeToString(keyCode)
                    + " action=" + action + " repeat=" + repeatCount
                    + (ok ? " 已发送" : " 无对应键码，交本地"));
        }
        return ok;
    }

    /** 日夜模式同步。车机切深色时手机端界面也跟着切。 */
    public void sendDayNight(boolean night) {
        if (!carLinkEnabled || adapter == null || !castStarted) {
            return;
        }
        final boolean ok = IccoaCarLink.sendDayNight(adapter, night);
        lastDayNight = night;
        lastDayNightSentAt = System.currentTimeMillis();
        log("日夜同步 → " + (night ? "夜间" : "白天") + (ok ? " 已发送" : " 被拒"));
    }

    /** 接听来电。 */
    public boolean answerCall() {
        return carLinkEnabled && IccoaCarLink.answerCall(adapter);
    }

    /** 挂断 / 拒接。 */
    public boolean hangUpCall() {
        return carLinkEnabled && IccoaCarLink.hangUpCall(adapter);
    }

    private boolean lastDayNight = false;
    private long lastDayNightSentAt = 0L;

    // ---- 车况：GPS 回传 ----
    // 车机定位通常比手机准（外置天线、隧道内惯性推算），发给手机后导航会明显更稳。
    // 每 5 秒一次，且只在投屏期间发 —— 空发没有意义，还耗电。

    private static final long GPS_INTERVAL_MS = 5000L;
    private int gpsSentCount = 0;

    private final Runnable gpsTick = new Runnable() {
        @Override
        public void run() {
            if (!castStarted || !carLinkEnabled || !gpsUplinkEnabled) {
                return;
            }
            try {
                Location loc = lastKnownLocation();
                if (loc == null) {
                    // 一分钟记一条，避免每 5 秒刷屏。
                    if (gpsSentCount % 12 == 0) {
                        log("GPS 回传跳过：没有可用的最近定位"
                                + (hasLocationPermission() ? "" : "（缺 ACCESS_FINE_LOCATION 权限）"));
                    }
                    main.postDelayed(this, GPS_INTERVAL_MS);
                    return;
                }
                if (!IccoaCarLink.sendGps(adapter, loc)) {
                    if (gpsSentCount % 12 == 0) {
                        log("GPS 回传被拒：SDK 未接受（GPSInfo 反射构造可能失败，详见 IccoaCarLink）");
                    }
                } else {
                    gpsSentCount++;
                    // 前两次各记一条，之后每 12 次（约 1 分钟）记一条，避免刷屏。
                    if (gpsSentCount <= 2 || gpsSentCount % 12 == 0) {
                        log("车况回传 GPS #" + gpsSentCount
                                + " (" + String.format(java.util.Locale.US, "%.5f,%.5f", loc.getLatitude(), loc.getLongitude())
                                + " 精度 " + Math.round(loc.getAccuracy()) + "m)");
                    }
                }
            } catch (Throwable t) {
                log("GPS 回传异常: " + t);
            }
            main.postDelayed(this, GPS_INTERVAL_MS);
        }
    };

    private boolean hasLocationPermission() {
        try {
            return appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void startGpsTick() {
        if (!gpsUplinkEnabled || !carLinkEnabled) {
            return;
        }
        main.removeCallbacks(gpsTick);
        main.postDelayed(gpsTick, 2000L);
    }

    private void cancelGpsTick() {
        main.removeCallbacks(gpsTick);
    }

    /**
     * 取最近一次已知位置。
     *
     * <p>用 {@code getLastKnownLocation} 而不是主动请求更新：车机定位通常由系统服务常驻维护，
     * 主动注册 LocationListener 会在投屏期间持续唤醒 GNSS，功耗不划算。没有权限或没有
     * 历史位置时返回 null，静默跳过。
     */
    private Location lastKnownLocation() {
        try {
            if (appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) appCtx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (Throwable ignored) {
                }
            }
            // 超过 2 分钟的定位不要：发给手机反而把导航带偏。
            if (best != null && System.currentTimeMillis() - best.getTime() > 120_000L) {
                return null;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

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
