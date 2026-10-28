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

        /**
         * 停机态变化（0.3.17）。
         *
         * <p>true = 用户/手机主动断开，车机已停止广播，等用户点「刷新配对码/连接」；
         * false = 已退出停机，重新开始广播。
         */
        default void onPaused(boolean paused) { }

        /** 广播连续失败的重试次数（0.3.17，A4）：UI 可据此提示"蓝牙广播重试中（第 N 次）"。 */
        default void onAdvertiseRetry(int attempt) { }
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
    // ==================================================================
    // SDK 连接状态码（0.3.17 修正）
    // ==================================================================

    /**
     * {@code onConnectStateChanged(deviceID, state, param)} 的<b>真实</b>语义。
     *
     * <p>0.3.16 及以前，这段代码把第二个参数当成 "oldState"、第三个当成 "newState"，
     * 于是所有分支实际是被 param 驱动的：state=1（断开）被漏掉、param=11（用户主动断开）
     * 没有任何分支。直接后果是手机走了、车机还以为会话在跑，只能等 30 秒自动刷新或
     * 45 秒协商超时 —— 也就是「最坏 30 秒、要手动介入」。
     *
     * <p>现在的映射（与社区 fork guo-zhc/MiAuto 的实测一致）：
     * <pre>
     *   1 DISCONNECTED        断开（param=11 表示用户/手机主动断开）
     *   2 CONNECTED           已连接
     *   3 CONNECT_FAILED      连接失败（param = 10003/10004/10006/10008 错误码）
     *   4 CAST_CONNECTED      投屏已连接
     *   5 CAST_DISCONNECTED   投屏已停止（param 恒为 -1，不带原因）
     *   6/7                   手机回桌面 / 回前台
     * </pre>
     */
    private static final int ST_DISCONNECTED = 1;
    private static final int ST_CONNECTED = 2;
    private static final int ST_CONNECT_FAILED = 3;
    private static final int ST_CAST_CONNECTED = 4;
    private static final int ST_CAST_DISCONNECTED = 5;
    private static final int ST_PHONE_GO_HOME = 6;
    private static final int ST_PHONE_BACK_FOREGROUND = 7;

    /** SDK 的 {@code UCarConnectState.DISCONNECT_BY_USER}。 */
    private static final int DISCONNECT_BY_USER = 11;

    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;

    /**
     * 用户/手机主动断开后的「停机」标志（0.3.17）。
     *
     * <p>置位后不再自动重播、不再自动回连，等待页提示「已断开 · 点刷新配对码重新连接」。
     * 只有用户动作（刷新配对码 / 重新连接）才清除。
     */
    private volatile boolean paused = false;

    /** 手机是否已进入握手（收到过任意 progress&gt;0）。握手期绝不重播（会换掉配对会话号）。 */
    private volatile boolean negotiating = false;

    /** 本会话内连续「无线建链类失败」次数，供 {@link ReconnectBackoff} 定档位。 */
    private int wirelessFailStreak = 0;

    /** state=5 之后等 state=1（带原因）的兜底计时。 */
    private final Runnable castStopReconsiderRunnable = this::onCastStopNoReason;

    /** 连接轮次诊断（A10）：每次重播 +1，连上/失败时打一行。 */
    private int connectRound = 0;
    private long connectRoundStartMs = 0L;

    /** 兜底 BLE 广播器（A7）：SDK 广播发不出去时接管发现。 */
    private FallbackBleAdvertiser fallbackAdv = null;
    /** SDK 是否已经调用过 startAdvertise（用于 5 秒自救判定）。 */
    private volatile boolean advertiseAttempted = false;
    /** 广播失败重试计数（A4）。 */
    private int advertiseRetryCount = 0;

    /**
     * 本端主动 disconnect() 的时刻（0.3.18，D1）。
     *
     * <p>SDK 把我们自己的 {@code disconnect()} 也回调成 {@code DISCONNECTED(param=11)} ——
     * 与"手机端用户主动断开"完全同一个值。不记这个时间戳，看门狗每次断开都会被当成
     * 用户断开 → 进入停机 → 只能手动点刷新（10/26 21:19、21:20 两次实测都是这样）。
     */
    private volatile long selfDisconnectAt = 0L;

    /** 首场"会话内重建解码器"已尝试次数（D2/D11）：最多 2 次，之后回到断开重连。 */
    private int decoderRebuildCount = 0;

    /** [5G] 每会话只打一次（D8）。 */
    private boolean fiveGLogged = false;

    /** 本轮的「结果」是否已记录（state 4 与 state 2 会各来一次，避免重复行）。 */
    private boolean roundResultLogged = false;
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
            // ★ 0.3.15：只有**真的进入了 AOA accessory 模式**才值得把无线关掉。
            //   10/25 的全量验证里，线插着但手机走的是 MTP/充电模式，connectType 恒为 1
            //   （P2P），AOA 一次都没建起来；旧判据（任何 UsbDevice）却每次都停广播 10 秒，
            //   冷启动因此白等 10~30 秒，还让角标误报「USB AOA 有线」。
            if (!isAoaAccessoryPresent()) {
                log("USB 优先：线已插但手机未进入 AOA 模式（MTP/充电），继续蓝牙广播");
                return;
            }
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
            if (!usbPriority || adapter == null || isAoaAccessoryPresent()) {
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
            // ★ 0.3.19（A2）：守卫从 castStarted 改成 lastProgress。
            //
            //   castStarted 只表示「我们调过 startCast」，预热也会把它置真；用它当守卫，
            //   10 秒回退就永远不执行 —— 10/27 18:12 的 USB 死循环正是这么来的：
            //     doAdvertise() → AOA 分支 → tryStartCast() → castStarted=true
            //     → 本 Runnable 被自己的守卫挡掉 → 永不广播 → 手机永远发现不了车机
            //     → 12s 起投看门狗断开 → 回到 doAdvertise() …… 循环 5 次、4 分 37 秒
            //   真正该看的是「手机有没有开始握手」：握手了 progress 就会 > 0。
            if (sessionActive || lastProgress > 0) {
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
    /** 空配总开关（0.3.13 默认关闭，见 tryStartCast 内注释）。 */
    private static final boolean PRIME_ENABLED = false;

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
        reAdvertiseDueAt = 0L;
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
     *
     * <p>★ 0.3.19（A5）：取「最早到期」，不再无脑覆盖。
     * 原先 disconnectSession() 先排 5000ms、看门狗紧接着排 800ms，日志里就是
     * 「计划重播：+5000ms / 计划重播：+800ms」两条挨着打 —— 看着像两个任务，
     * 实际是靠 removeCallbacks 把 5 秒那条顶掉。一旦调用顺序反过来（先 800 再 5000），
     * 短延时就被吃掉了，本该 0.8 秒重连的会话要干等 5 秒。
     */
    private void scheduleReAdvertise(long delayMs) {
        final long due = System.currentTimeMillis() + delayMs;
        if (reAdvertiseDueAt > 0L && reAdvertiseDueAt <= due) {
            log("计划重播：+" + delayMs + "ms（已有更早的 +"
                    + Math.max(0L, reAdvertiseDueAt - System.currentTimeMillis()) + "ms，保留前者）");
            return;
        }
        cancelReAdvertise();
        main.postDelayed(reAdvertiseRunnable, delayMs);
        reAdvertiseDueAt = due;
        log("计划重播：+" + delayMs + "ms");
    }

    /** 计划中的重播到期时间（0 表示没有排程）。A5 用它做「取最早」比较。 */
    private volatile long reAdvertiseDueAt = 0L;

    /** 取消已排程的重播，并把 A5 的到期时间一并清掉（否则会被误判成「已有更早排程」）。 */
    private void cancelReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        reAdvertiseDueAt = 0L;
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
        if (paused) {
            log("自动刷新：已停机，跳过本轮");
            return;
        }
        if (negotiating) {
            // ★ 0.3.17：握手期绝不重播。重播会换广播序列号（= 配对会话号），
            //   手机端会把正在进行的握手当成另一个会话 —— 这就是"手机提示连接失败"的来源。
            log("自动刷新：手机正在握手（" + lastProgress + "%），不打断");
            scheduleAutoRefresh();
            return;
        }
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

    /**
     * 协商自救（A3）：手机一旦开始握手就不再重播（重播会换掉广播序列号 = 配对会话号，
     * 正在进行的握手会被手机端当成另一个会话）。但握手也可能无疾而终，所以留一条 90 秒的
     * 自救通道 —— 到点还没连上，才允许重新广播一次。
     */
    private static final long NEGOTIATION_RESCUE_MS = 90_000L;

    private final Runnable negotiationRescueRunnable = () -> {
        if (paused) {
            log("协商自救：已停机，跳过");
            return;
        }
        if (sessionActive || surfaceReady) {
            log("协商自救：已连上，跳过");
            return;
        }
        log("★ 协商中断自救：握手超过 " + (NEGOTIATION_RESCUE_MS / 1000) + " 秒无结果 → 重播一次");
        negotiating = false;
        logConnectRoundResult("协商超时自救");
        reAdvertise();
    };

    private void armNegotiationRescue() {
        main.removeCallbacks(negotiationRescueRunnable);
        main.postDelayed(negotiationRescueRunnable, NEGOTIATION_RESCUE_MS);
    }

    private void cancelNegotiationRescue() {
        main.removeCallbacks(negotiationRescueRunnable);
    }

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
        recomputeVideoSize();
    }

    /**
     * 按「面板尺寸 + 设置里的视频分辨率」重算投屏画布（0.3.19 E5）。
     *
     * <p>原先这段只在 {@link #setScreenSize} 里跑，而 setScreenSize 只有 MainActivity
     * 启动时调一次 —— 用户在设置里改了分辨率、点「保存并重启」之外的任何操作都不会生效。
     * 10/28 报告里 12 场会话的「投屏尺寸」全部是 1280x672，一帧没变过，就是这么来的。
     * 现在 {@code buildConfig()} 每次都会调用本方法，改完设置下一次连接即生效。
     */
    private void recomputeVideoSize() {
        // ★ 投屏画布上限跟随「视频分辨率」设置项（Settings.resolutionId）。
        //   默认 720p：1920x990@30 约 190 万像素/帧，H.264 码率 8~12 Mbps，余量不足。
        //   降到 480p（约 43 万像素）码率可再降一档，用于排查「是不是带宽/解码撑不住」。
        int capW = 1280, capH = 720;
        int rid = -1;
        try {
            rid = new com.andrerinas.openheadunit.utils.Settings(appCtx).getResolutionId();
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
                + "（上限 " + capW + "x" + capH + "，resolutionId=" + rid + "，已按 16 对齐）");
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
            // ★ D5（0.3.18）：会话里的 phoneId 常常是 "-1"（SDK 没回填设备号）。
            //   旧实现会拿 lastDeviceId 去猜 —— 那是**上一场**的设备，于是 USB 会话
            //   会被贴上上一场的 "P2P"（21:21 那场角标误报 5GHz 就是这么来的）。
            //   现在：设备号无效就明确置"未知"，由角标按 AOA 证据兜底。
            String id = phoneId;
            if (id == null || id.isEmpty() || "-1".equals(id)) {
                stats.connectType = -1;
                log("[通道判定] 设备号无效（phoneId=" + phoneId + "）→ 本次通道未知，按 AOA 证据判定");
                return;
            }
            UCarCommon.MobileDevice dev = adapter.getMobileDevice(id);
            if (dev == null) {
                stats.connectType = -1;
                log("[通道判定] 未找到设备（phoneId=" + phoneId + "）→ 本次通道未知");
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
        stats.consumeHadFrame();
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
        // ★ 0.3.20：诊断计时也要跟着清。
        //   投屏页关掉后 SDK 仍停在 progress 100，下一场是「恢复」而不是「新协商」，
        //   tProgress100 留着上一场的值 → 首帧那行打出「距 progress100 152095ms」
        //   （10/28 22:xx 的第三次会话就是这个），报告一打开就像出了大事故。
        tProgress90 = 0L;
        tProgress100 = 0L;
        logCapabilities();
        cancelGpsTick();
        stopLinkStats();
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
    /** 上一行「依据」文案，用于 A4 去重。 */
    private volatile String transportEvidence = null;
    /** 本场会话是否已经判定过通道（ct=-1 时用来区分「还没判」和「判不了」）。 */
    private volatile boolean transportResolved = false;

    public String getTransportLabel() { return transportLabel; }

    /**
     * 判定并上报当前数据通道（0.3.7；0.3.15 改为以 SDK 的 connectType 为准）。
     *
     * <p>旧实现先看「有没有 USB 设备插着」——10/25 的全量验证因此大面积误报：
     * 手机插着线充电、视频实际跑在 P2P 上（<code>[通道判定] connectType=1</code>），
     * 角标却写「USB AOA 有线」。用户的观感就是「插过一次 USB 以后永远显示 USB」。
     *
     * <p>现在唯一的真相源是 {@code MobileDevice.getConnectType()}：
     * 2 = AOA 有线、1 = P2P、3 = SoftAP；拿不到时才退回「AOA 配件是否真的在」。
     */
    private void resolveTransportLabel() {
        final int ct = stats.connectType;
        final boolean aoa = isAoaActive();

        // ★ D4/D5（0.3.18）判定优先级：AOA 证据 > connectType > 频段。
        //   之前只看 connectType，而 dev=-1 的会话读不到类型、还会沿用上一场的值，
        //   于是 USB 会话被标成 P2P 5GHz（10/26 21:21 实测）。
        if (aoa) {
            setTransport("USB AOA 有线", "AOA 设备/usb0 接口");
            return;
        }
        if (ct == 2) {
            setTransport("USB AOA 有线", "connectType=2");
            return;
        }
        if (ct == 3) {
            setTransport("SoftAP Wi-Fi", "connectType=3");
            return;
        }

        // ★ 0.3.19（A4）：ct=-1 且没有 AOA 证据 → 这一场还没拿到类型，先不下结论。
        //
        //   10/28 报告里每场会话都会先打一条「connectType=-1 / 频段=5180MHz」，
        //   20~40ms 后又打一条「connectType=1 / 频段=5180MHz」——前者是猜测，后者才是事实。
        //   等 state=2 把类型带回来再判，角标才不会先闪一下错的依据。
        if (ct == -1 && !transportResolved) {
            return;
        }

        // 到这里是 P2P（ct=1）或未知：读组的实际频率来细分 2.4G / 5G。
        try {
            android.net.wifi.p2p.WifiP2pManager wm = (android.net.wifi.p2p.WifiP2pManager)
                    appCtx.getSystemService(Context.WIFI_P2P_SERVICE);
            if (wm != null) {
                android.net.wifi.p2p.WifiP2pManager.Channel ch =
                        wm.initialize(appCtx, main.getLooper(), null);
                wm.requestGroupInfo(ch, group -> {
                    String l = "P2P Wi-Fi（频段未知）";
                    int freq = -1;
                    if (group != null) {
                        try {
                            Object f = group.getClass().getMethod("getFrequency").invoke(group);
                            freq = (Integer) f;
                            if (freq >= 4900) {
                                l = "P2P Wi-Fi 5GHz";
                            } else if (freq > 0) {
                                // A9：只提示、不打断。2.4G 是「卡顿」最重要的归因线索。
                                l = "P2P Wi-Fi 2.4GHz（建议改 5G）";
                            }
                        } catch (Throwable ignored) {
                            // hidden API 被裁剪 → 频段未知
                        }
                    }
                    final int f2 = freq;
                    if (!fiveGLogged) {
                        fiveGLogged = true;
                        log("[5G] 组频段=" + (f2 > 0 ? f2 + "MHz" : "读不到")
                                + " 判定=" + (f2 >= 4900 ? "5G" : f2 > 0 ? "2.4G" : "未知"));
                    }
                    setTransport(l, "connectType=" + ct + " / 频段=" + (f2 > 0 ? f2 + "MHz" : "未知"));
                });
                return;
            }
        } catch (Throwable ignored) {
        }
        setTransport("P2P Wi-Fi（频段未知）", "connectType=" + ct);
    }

    /** 统一出口：写标签 + 打一行「依据」（A4：同一会话同一结论不重复刷）。 */
    private void setTransport(String label, String evidence) {
        final boolean labelChanged = !label.equals(transportLabel);
        final boolean evidenceChanged = evidence == null || !evidence.equals(transportEvidence);
        transportLabel = label;
        transportEvidence = evidence;
        transportResolved = true;
        // 0.3.18 的注释写着「D8 同一会话不重复刷」，但代码里没有任何判断，
        // 于是一场会话打 4 条一模一样的「[通道] 当前数据通道」—— 日志预算全被它吃了。
        if (labelChanged || evidenceChanged) {
            log("[通道] 当前数据通道：" + label + "（依据：" + evidence + "）");
        }
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
                selfDisconnectAt = System.currentTimeMillis();
                adapter.disconnect();
            } catch (Throwable t) {
                log("disconnect 异常: " + t);
            }
            sdkInitialized = false;
            sdkReady = false;
            castStarted = false;
            sessionActive = false;
            main.removeCallbacks(autoRefreshRunnable);
            cancelReAdvertise();
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

                startConnectRound("初始化成功");
                main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
                // ★ 0.3.17（A7）：SDK 初始化成功却迟迟没走到广播（原生库/线程异常），
                //   5 秒后由兜底广播接管发现 —— 否则手机上永远搜不到车机。
                main.postDelayed(() -> {
                    if (!advertiseAttempted && !paused) {
                        log("! 初始化 5s 仍未广播 → 兜底广播接手");
                        ensureFallbackAdvertise("SDK 5s 未广播");
                    }
                }, 5000L);
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
        if (paused) {
            log("重播被拒：当前处于停机态（等用户点连接）");
            return;
        }
        startConnectRound("重新广播");
        fallbackStarted = false;
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
        // 用户的明确动作：无论此前是否停机，都恢复广播。
        if (paused) log("■ 手动刷新 → 退出停机态");
        paused = false;
        wirelessFailStreak = ReconnectBackoff.resetStreak();
        try { listener.onPaused(false); } catch (Throwable ignored) { }
        reAdvertise();
        scheduleAutoRefresh();
    }

    public void stop() {
        log("=== 停止 ===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            selfDisconnectAt = System.currentTimeMillis();
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        cancelReAdvertise();
        main.removeCallbacks(usbPriorityFallback);
        cancelAutoRefresh();
        cancelNegotiationRescue();
        main.removeCallbacks(advertiseRetryRunnable);
        main.removeCallbacks(castStopReconsiderRunnable);
        if (fallbackAdv != null) fallbackAdv.stop();
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
    // ==================================================================
    // 会话生命周期（0.3.17）
    // ==================================================================

    /**
     * 统一收口：任何一条路径判定"会话结束"都走这里。
     *
     * <p>旧实现里清状态的代码散在 6 个分支里，漏一处就会出现「手机都走了、车机还以为会话在跑」，
     * 于是 30 秒自动刷新一直打印「会话进行中，跳过本轮」，用户只能重启 App。
     */
    private void endSession(String reason) {
        sessionActive = false;
        negotiating = false;
        castStarted = false;
        surfaceReady = false;
        castRebuildCount = 0;
        lastPinCode = null;
        cancelEarlyBail();
        cancelCastStallWatchdog();
        cancelConnectingTimeout();
        cancelNegotiationRescue();
        main.removeCallbacks(surfaceWatchdog);
        linkLostArmed = false;
        fiveGLogged = false;
        main.removeCallbacks(linkLostRunnable);
        log("· 会话清理（" + reason + "）");
    }

    /**
     * 进入「停机」：用户/手机主动断开后不再自动回连（0.3.17 的明确决策）。
     *
     * <p>只停广播、不动 SDK 之外的东西；用户点「刷新配对码」或主页「连接」即恢复。
     */
    private void enterPaused(String why) {
        if (paused) {
            // D8：本端断开与手机端断开回执会前后脚各来一次，只处理第一条。
            return;
        }
        paused = true;
        sessionActive = false;
        negotiating = false;
        lastPinCode = null;
        cancelReAdvertise();
        main.removeCallbacks(autoRefreshRunnable);
        main.removeCallbacks(usbPriorityFallback);
        try {
            if (adapter != null) adapter.stopAdvertise();
        } catch (Throwable ignored) {
        }
        if (fallbackAdv != null && fallbackAdv.isRunning()) fallbackAdv.stop();
        log("■ 已停机（" + why + "）→ 不再自动重播，等用户点「刷新配对码/连接」");
        try { listener.onPaused(true); } catch (Throwable ignored) { }
        // 手机端主动断开时投屏页还停在最后一帧，必须让它退回等待页（否则画面看着像卡死）。
        main.post(() -> { try { listener.onLinkLost(); } catch (Throwable ignored) { } });
    }

    /** 用户动作恢复：清停机标志并重播。主页「连接」「刷新配对码」走这里。 */
    public void resumeLink() {
        if (!sdkReady || adapter == null) {
            log("恢复连接失败：SDK 尚未就绪");
            return;
        }
        if (paused) log("■ 用户要求恢复连接 → 退出停机态");
        paused = false;
        wirelessFailStreak = ReconnectBackoff.resetStreak();
        try { listener.onPaused(false); } catch (Throwable ignored) { }
        reAdvertise();
        scheduleAutoRefresh();
    }

    /** 连接轮次计时起点（每次重播/起播算一轮）。 */
    private void startConnectRound(String why) {
        connectRound++;
        roundResultLogged = false;
        connectRoundStartMs = System.currentTimeMillis();
        log("[连接] 第 " + connectRound + " 轮开始（" + why + "）");
    }

    private void logConnectRoundResult(String result) {
        // state=4（投屏已连）与 state=2（已连）会前后脚各来一次，只记第一条（D8）。
        if (roundResultLogged) return;
        if (!"成功".equals(result)) {
            logRoundLine(result);
            return;
        }
        roundResultLogged = true;
        // ★ 0.3.19（A3）：成功后等 connectType 落定再打这一行。
        //
        //   10/28 报告里 12 条「第 N 轮结果」有 10 条写「通道=未知」——
        //   因为 state=4(CAST_CONNECTED) 到达时 connectType 还没写进 stats，
        //   要等后面那条 state=2(CONNECTED) 才带上类型。
        // ★ 0.3.20：固定延后 600ms 仍不够 —— 10/28 22:xx 那场 state=4 与 state=2
        //   相隔 850ms，结果还是打成「未知」。改成轮询：connectType 一有值就打，最多等 2 秒。
        final int round = connectRound;
        final long startMs = connectRoundStartMs;
        final int stage = lastProgress;
        waitAndLogRoundLine(round, startMs, stage, 0);
    }

    /** A3 的轮询体：等 connectType 落定（最多 2 秒）再打轮结果。 */
    private void waitAndLogRoundLine(int round, long startMs, int stage, int attempt) {
        final boolean ready = stats.connectType != -1 || isAoaActive() || attempt >= 8;
        if (!ready) {
            main.postDelayed(() -> waitAndLogRoundLine(round, startMs, stage, attempt + 1), 250L);
            return;
        }
        long ms = startMs == 0L ? -1L : System.currentTimeMillis() - startMs;
        log("[连接] 第 " + round + " 轮结果=成功 通道=" + channelShort()
                + (ms >= 0 ? " 用时=" + ms + "ms" : "") + " 阶段=" + stage + "%");
    }

    /** 立即打一行轮结果（失败/断开等不需要等 connectType 的场合）。 */
    private void logRoundLine(String result) {
        long ms = connectRoundStartMs == 0L ? -1L : System.currentTimeMillis() - connectRoundStartMs;
        log("[连接] 第 " + connectRound + " 轮结果=" + result + " 通道=" + channelShort()
                + (ms >= 0 ? " 用时=" + ms + "ms" : "") + " 阶段=" + lastProgress + "%");
    }

    /** 当前通道的短名（诊断用，D10）：USB / P2P / 未知。 */
    private String channelShort() {
        if (isAoaActive()) return "USB";
        int ct = stats.connectType;
        if (ct == 2) return "USB";
        if (ct == 1 || ct == 3) return "P2P";
        return "未知";
    }

    /**
     * state=5（投屏已停止）之后 3 秒仍没等到 DISCONNECTED(1) 的兜底处理。
     *
     * <p>state=5 的 param 恒为 -1，单看它分不清「抖动」还是「用户断开」。正常情况下
     * 手机/用户主动断开会在毫秒级内补一条 state=1 + param=11；等不到就说明是异常断链。
     */
    private void onCastStopNoReason() {
        if (!castStarted && !sessionActive) return;
        if (paused) {
            log("· CAST_DISCONNECTED 后 3s 无原因，但已停机 → 不再重播");
            return;
        }
        log("★ CAST_DISCONNECTED 后 3s 仍无 DISCONNECTED(1) → 按异常断连处理");
        endSession("投屏停止无原因");
        logConnectRoundResult("投屏停止无原因");
        scheduleReAdvertise(ReconnectBackoff.nextDelayMs(false, 0));
        scheduleAutoRefresh();
    }

    /**
     * 僵死画面防护：画面曾经出来过、链路却掉了 → 2 秒确认窗后退出投屏页。
     * 只认 state=1/5（旧代码看的 "-1" 是 param，等于从来没真正触发过）。
     */
    private void armLinkLostIfNeeded(int state) {
        if (castStarted && everSurfaceReady && !linkLostArmed
                && (state == ST_DISCONNECTED || state == ST_CAST_DISCONNECTED)) {
            linkLostArmed = true;
            log("· 画面曾出过、链路断开 → 2 秒内未恢复则退出投屏页（僵死防护）");
            main.postDelayed(linkLostRunnable, 2000L);
        }
    }

    /**
     * 本端断开当前会话（自动恢复路径用）。
     *
     * <p>投屏页的「断开」按钮走 {@link #userDisconnect()}：那是人的决定，必须停机。
     * 本方法只给看门狗/重播链路用，断完会重新广播。
     */
    public void disconnectSession() {
        log("=== 断开当前会话（保持广播）===");
        if (adapter != null) {
            // ★ pauseCast(false)：不向手机下发 "GotoBackground"。
            //   紧跟着就是 disconnect()，再让手机把投屏应用退回后台毫无意义，
            //   反而可能让手机进入「被切走」状态、推迟自动重连 —— 而重连正是
            //   冷启动首场之后恢复画面的唯一路径。
            try { adapter.pauseCast(false); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            selfDisconnectAt = System.currentTimeMillis();
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

    /**
     * 用户主动断开（投屏页「断开」按钮 / 主页停止）。
     *
     * <p>0.3.17 的明确决策：用户断开后<b>保持停机</b>，不再自动重播、不再自动回连 ——
     * 旧实现断完 5 秒又广播，手机端 CarWith 立刻把车机连回来，用户看到的就是「点了断开又连上」。
     */
    public void userDisconnect() {
        log("=== 用户主动断开（保持停机）===");
        try { if (adapter != null) adapter.pauseCast(false); } catch (Throwable ignored) { }
        selfDisconnectAt = System.currentTimeMillis();
        try { if (adapter != null) adapter.disconnect(); } catch (Throwable ignored) { }
        endSession("用户主动断开");
        enterPaused("用户主动断开");
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
        if (usbPriority && isAoaAccessoryPresent()) {
            log("USB 优先：检测到 AOA 配件，跳过蓝牙广播，只等 AOA 建链（10 秒无果则回退）");
            main.removeCallbacks(usbPriorityFallback);
            main.postDelayed(usbPriorityFallback, USB_PRIORITY_FALLBACK_MS);
            tryStartCast();
            return;
        }

        advertiseAttempted = true;
        biasP2pTo5gIfRequested();
        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        if (!ok) {
            onAdvertiseFailed("startAdvertise 返回 false");
        } else {
            advertiseRetryCount = 0;
            // SDK 广播成功了，兜底广播必须让位，避免手机看到两个同源广播
            if (fallbackAdv != null && fallbackAdv.isRunning()) {
                log("SDK 广播已成功 → 停止兜底广播");
                fallbackAdv.stop();
            }
            fallbackStarted = false;
        }
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
            if (PRIME_ENABLED && !handoffPrimed) {
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

    // ==================================================================
    // 广播失败自愈 + 兜底广播（0.3.17 的 A4 / A7）
    // ==================================================================

    private boolean fallbackStarted = false;

    private final Runnable advertiseRetryRunnable = () -> {
        if (paused || adapter == null) return;
        if (sessionActive || castStarted) return;
        log("广播重试（第 " + (advertiseRetryCount + 1) + " 次）");
        doAdvertise();
    };

    /**
     * SDK 广播失败后的自愈：1.2s → 2.4s → 4.8s → 9.6s，最长 10s，永不放弃。
     *
     * <p>旧实现在这里只打一行日志就结束了，而 pinWatchdog 又只在"成功路径"排程、
     * 两次后放弃 —— 一次 startAdvertise 失败就等于整台车机失联，只能重启 App。
     * 10/25 的 6 次「回退广播 失败」全都是这么卡死的。
     */
    private void onAdvertiseFailed(String why) {
        advertiseRetryCount++;
        long delay = Math.min(1200L * (1L << Math.min(advertiseRetryCount - 1, 3)), 10_000L);
        log("✗ 广播失败（" + why + "，连续第 " + advertiseRetryCount + " 次）→ " + delay + "ms 后重试");
        try { listener.onAdvertiseRetry(advertiseRetryCount); } catch (Throwable ignored) { }
        // 连续失败时同时拉起兜底广播：SDK 的扩展广播在某些蓝牙栈上"返回成功但发不出去"，
        // 这条路径只能靠传统 31 字节广播补上。
        if (advertiseRetryCount >= 2) {
            ensureFallbackAdvertise("SDK 广播连续失败");
        }
        main.removeCallbacks(advertiseRetryRunnable);
        main.postDelayed(advertiseRetryRunnable, delay);
    }

    /**
     * 「优先 5GHz」的建组前偏置（0.3.17，A9）。
     *
     * <p>用隐藏 API {@code WifiP2pManager.setWifiP2pChannels} 把整机 P2P 工作信道限制到
     * 149（UNII-3）。这是<b>尽力而为</b>：SDK 建组时可能自己复位信道，抹掉这层限制 ——
     * 抹掉了就退化成"不干预"，绝不会让连接失败。建组后的真实频段另由
     * {@link #resolveTransportLabel()} 事后校验。
     */
    private void biasP2pTo5gIfRequested() {
        if (!IccoaPrefs.getBool(appCtx, IccoaPrefs.KEY_5G_ONLY, false)) return;
        try {
            android.net.wifi.p2p.WifiP2pManager wm =
                    (android.net.wifi.p2p.WifiP2pManager) appCtx.getSystemService(Context.WIFI_P2P_SERVICE);
            if (wm == null) {
                log("[5G] 偏置跳过：WifiP2pManager 不可用");
                return;
            }
            android.net.wifi.p2p.WifiP2pManager.Channel ch =
                    wm.initialize(appCtx, main.getLooper(), null);
            if (ch == null) {
                log("[5G] 偏置跳过：Channel 初始化失败");
                return;
            }
            java.lang.reflect.Method m = wm.getClass().getMethod("setWifiP2pChannels",
                    android.net.wifi.p2p.WifiP2pManager.Channel.class,
                    int.class, int.class,
                    android.net.wifi.p2p.WifiP2pManager.ActionListener.class);
            m.invoke(wm, ch, 0, 149, new android.net.wifi.p2p.WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { log("[5G] 工作信道偏置已下发：149 (UNII-3)"); }
                @Override public void onFailure(int reason) { log("[5G] 工作信道偏置被拒 reason=" + reason); }
            });
        } catch (Throwable t) {
            log("[5G] 工作信道偏置不可用（隐藏 API 被裁剪）: " + t);
        }
    }

    /**
     * 拉起兜底 BLE 广播（{@link FallbackBleAdvertiser}）。
     *
     * <p>延迟 800ms 是必需的：序列号由 SDK 在构建广播数据时写进 {@code serial_num_prefs}，
     * 两条广播必须镜像同一个序列号 —— 否则手机端会把同一台车看成两个配对会话。
     */
    private void ensureFallbackAdvertise(String why) {
        if (paused || fallbackStarted) return;
        fallbackStarted = true;
        if (fallbackAdv == null) {
            fallbackAdv = new FallbackBleAdvertiser(appCtx, this::log);
        }
        log("→ 启动兜底 BLE 广播（" + why + "）");
        main.postDelayed(() -> {
            if (paused) return;
            try {
                fallbackAdv.start(generateCarId(), sessionActive);
            } catch (Throwable t) {
                log("✗ 兜底广播启动异常: " + t);
                fallbackStarted = false;
            }
        }, 800L);
    }

    private void schedulePinWatchdog() {
        main.removeCallbacks(pinWatchdog);
        pinRetryCount = 0;
        main.postDelayed(pinWatchdog, 8000L);
    }

    private final Runnable pinWatchdog = new Runnable() {
        @Override
        public void run() {
            if (lastPinCode != null) return;      // 已经有码，正常
            if (paused) return;                    // 停机态不重播
            if (negotiating) {                     // 握手期重播 = 打断配对会话
                log("配对码看门狗：手机正在握手，跳过重播");
                main.postDelayed(pinWatchdog, 8000L);
                return;
            }
            if (pinRetryCount >= 6) {
                log("✗ 连续 6 次重播都没有配对码，交给兜底广播/长退避（不再放弃）");
                return;
            }
            pinRetryCount++;
            log("✗ 8s 未收到配对码 → 重新广播（第 " + pinRetryCount + " 次）");
            if (pinRetryCount == 1) {
                // SDK 认为广播成功、却迟迟没有配对码：典型是"扩展广播发不出去"的老蓝牙栈。
                ensureFallbackAdvertise("8s 无配对码");
            }
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

        // ★ 0.3.13：空配整体退役（PRIME_ENABLED=false，代码保留供回退）。
        //   07:17 两次冷启动实测证明空配是首场黑的直接元凶：setOutputSurface 交接
        //   不真正生效（帧仍渲染在离屏面，真实屏幕像素恒 0），且 prime 管线把 SDK
        //   的 surface-ready 内部状态标记掉 —— 视频已初始化、onSurfaceReady 却在
        //   首场永不回调。而它想防的「首帧被清队列」在现时序下不存在：投屏页在
        //   progress 10 自动打开，真实 startCast 距第一帧约 5.8s，configure 直接在
        //   真实 Surface 上完成即可，关键帧无损。
        if (PRIME_ENABLED && !handoffPrimed) {
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
            startLinkStats();
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
                    // ★ 0.3.16：这条路径也要记 stats —— 0.3.15 只在像素路径调了
                    //   stats.onFirstFrame()，于是「onSurfaceReady 出画」的会话汇总里
                    //   依然写「首帧=未就绪 / 结果=黑屏」（08:22 报告的最后一条就是）。
                    stats.onFirstFrame();
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
    /**
     * 首屏快判窗口（0.3.12 重定义；0.3.14 再放宽 2500 → 4000ms）。
     *
     * <p>900ms 时代的判据是「无像素即死」，但 06:39 日志证明首连时手机端画面本身
     * 接近纯黑（CarWith 深色确认页），像素采样永远读 0 —— 好会话被冤杀，重建 3 轮。
     * 0.3.12 起超时只触发一次**无感重绑**（pauseCast(false)+重投，手机无感知），
     * 不是判死；连续两窗都无任何首帧信号才断会话重播。
     *
     * <p>0.3.14 依据（07:38 首份 0.3.13 实测）：空配退役后 90→100 只需 264ms，
     * 但 video 管线初始化（onCastVideoInitialized / onWfdSinkReady）实测要
     * startCast 后 ~2.55s 才完成 —— 2.5s 窗口在管线就绪前 50ms 就到期，
     * 第一场被冤杀一次。参照第二场模式（onWfdSinkReady → ~200ms 出首帧），
     * 4s 给足「init → 首帧」余量；最坏情况多等 1.5s 才走重绑→断开老链兜底。
     *
     * <p>★ 0.3.19（A8）：4000 → 2000ms。
     * 10/28 报告里 6 次成功出画全部落在 <b>43 / 423 / 430 / 433 / 454 / 455 ms</b>，
     * 没有一次超过 0.5 秒。也就是说这个窗口一旦到期，画面就<b>不会再来</b>了，
     * 继续等 4 秒纯属让用户多盯 2 秒黑屏。缩到 2 秒仍有 4 倍余量。
     */
    private static final long COLD_START_TIMEOUT_MS = 2000L;

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
        } else if (isAoaAccessoryPresent()) {
            // 0.3.15：只有真的 AOA 链路才给 USB 专用超时；线插着但走 Wi-Fi 时用常规窗口
            timeout = COLD_START_TIMEOUT_USB_MS;
        } else {
            timeout = COLD_START_TIMEOUT_MS;
        }
        main.postDelayed(surfaceWatchdog, timeout);
    }

    /**
     * 会话内重建解码器（0.3.18，D2）。
     *
     * <p>冷启动第一场的顽症：progress 100、状态 CONNECTED，但 {@code onSurfaceReady} 永不回调、
     * 像素恒 0；同一进程的第二场却 300~800ms 就出画。差别不在链路，在<b>解码器实例</b>。
     *
     * <p>反编译 {@code CastManager.stopCast()} 只有一行：{@code mVideoPlayer.release()} ——
     * 它<b>不动 WFD 会话、不动手机端</b>；而 {@code CastManager.startCast()} 每次都会
     * {@code new VideoPlayerV2(...)} 再 {@code init(surface, w, h)}。也就是说：
     * 只要"release + 重建一个新的"，就等价于第二场那个能出画的解码器，而链路完全不用拆。
     *
     * <p>反射路径：{@code UCarAdapter.mCastManager}（私有字段）→ {@code CastManager.stopCast()}；
     * 然后走我们自己的 {@code tryStartCast()}。任何一步失败都返回 false，调用方回退到原来的
     * "重绑输出面"逻辑，行为不比 0.3.17 差。
     */
    private boolean rebuildDecoderInSession() {
        if (adapter == null) return false;
        try {
            java.lang.reflect.Field f = adapter.getClass().getDeclaredField("mCastManager");
            f.setAccessible(true);
            Object cm = f.get(adapter);
            if (cm == null) {
                log("[重建] 反射拿不到 CastManager，回退重绑");
                return false;
            }
            // ★ 0.3.19（A1）：getMethod → getDeclaredMethod。
            //
            //   0.3.18 用 getMethod("stopCast") 一直抛 NoSuchMethodException，
            //   日志里写着 "CastManager.stopCast []" —— 类名对得上、零参也对得上，
            //   唯一可能是**可见性**。解 classes3.dex 的 class_data 后确认：
            //     direct PRIVATE V stopCast()   access=0x20002
            //   而 pauseCast / release / stopWfdServer 都是 public virtual。
            //   Class.getMethod() 只搜 public，private 必须 getDeclaredMethod。
            java.lang.reflect.Method stop;
            try {
                stop = cm.getClass().getDeclaredMethod("stopCast");
            } catch (NoSuchMethodException nsme) {
                // 自证：把这个类真实存在的方法名列出来，下次不用再猜。
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Method m : cm.getClass().getDeclaredMethods()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(m.getName()).append('/').append(m.getParameterCount());
                }
                log("[重建] CastManager 无 stopCast，实际方法: " + sb);
                return false;
            }
            stop.setAccessible(true);

            // ★ 0.3.19（B1）：记解码器实例指纹。
            //   要验证的假设是「进程内第一个 VideoPlayerV2 必黑、复用的就能出画」——
            //   只有把每次 release/新建后的 identityHashCode 记下来，下一轮日志才能证伪。
            final String before = videoPlayerId(cm);
            stop.invoke(cm);
            castStarted = false;
            tryStartCast();
            log("[重建] 已释放 VideoPlayerV2(" + before + "，stopCast，不动会话）→ 立即重建并重投");
            // startCast 是 post 到 SDK worker 的，立刻读 mVideoPlayer 还是 null。
            // 延后一拍再读，才能看到「重建出来的到底是不是新对象」（B1）。
            final Object cmRef = cm;
            main.postDelayed(() -> log("[重建] 重建后 VideoPlayerV2=" + videoPlayerId(cmRef)), 800L);
            return true;
        } catch (Throwable t) {
            log("[重建] stopCast 反射不可用: " + t);
            return false;
        }
    }

    /** 读 CastManager.mVideoPlayer 的实例指纹（B1）。拿不到就打 n/a，不抛。 */
    private String videoPlayerId(Object castManager) {
        try {
            java.lang.reflect.Field fp = castManager.getClass().getDeclaredField("mVideoPlayer");
            fp.setAccessible(true);
            Object vp = fp.get(castManager);
            return vp == null ? "null" : (vp.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(vp)));
        } catch (Throwable t) {
            return "n/a";
        }
    }

    /**
     * 让 SDK 拿到音频焦点（0.3.19 E3-3）。
     *
     * <p>反编译确认的反射路径（两处持有同一个实例，任取其一）：
     * <pre>
     *   UCarAdapter.mUCarAudioManager            : UCarAudioManager
     *   UCarAdapter.mCastManager.mUCarAudioManager : UCarAudioManager
     *   UCarAudioManager.allowGainAudioFocus()     : public void
     * </pre>
     */
    private void allowGainAudioFocusForSdk() {
        if (adapter == null) return;
        Object am = null;
        try {
            java.lang.reflect.Field fa = adapter.getClass().getDeclaredField("mUCarAudioManager");
            fa.setAccessible(true);
            am = fa.get(adapter);
        } catch (Throwable ignored) {
            // 退到 CastManager 里的那一份
        }
        if (am == null) {
            try {
                java.lang.reflect.Field fc = adapter.getClass().getDeclaredField("mCastManager");
                fc.setAccessible(true);
                Object cm = fc.get(adapter);
                if (cm != null) {
                    java.lang.reflect.Field fa2 = cm.getClass().getDeclaredField("mUCarAudioManager");
                    fa2.setAccessible(true);
                    am = fa2.get(cm);
                }
            } catch (Throwable ignored) {
            }
        }
        if (am == null) {
            log("[音频] 反射拿不到 UCarAudioManager，本次无法放行焦点");
            return;
        }
        try {
            java.lang.reflect.Method m = am.getClass().getMethod("allowGainAudioFocus");
            m.invoke(am);
            log("[音频] allowGainAudioFocus() 已调用");
        } catch (Throwable t) {
            log("[音频] allowGainAudioFocus 失败: " + t);
        }
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

            // ★ 0.3.12：冷启动与稳态统一为「重绑优先」。
            //   旧逻辑认为首场手机不推流、重绑注定无效，一超时就直接断会话 ——
            //   06:39 的 logcat 推翻了这个前提：onCastVideoInitialized / uibc->started
            //   都到了，只是画面暗到像素采样读 0。重绑（setOutputSurface 路径）不拆
            //   链路、手机无感知，是更安全的中间档；连续两窗都哑才断会话重播。
            final long window = everSurfaceReady ? SURFACE_READY_TIMEOUT_MS : COLD_START_TIMEOUT_MS;

            if (castRebuildCount == 0) {
                castRebuildCount++;
                // ★ D2（0.3.18）：本进程还没出过画面 → 先试"会话内重建解码器"。
                //   这是唯一对症的动作：release 旧 VideoPlayerV2 再 new 一个，
                //   WFD 会话与手机端全程无感。最多 2 次，之后回到断开重连（D11）。
                if (!everSurfaceReady && decoderRebuildCount < 2 && rebuildDecoderInSession()) {
                    decoderRebuildCount++;
                    log("· 首场重建解码器第 " + decoderRebuildCount + " 次，再给 " + window + "ms 看首帧");
                    main.removeCallbacks(surfaceWatchdog);
                    main.postDelayed(surfaceWatchdog, window);
                    return;
                }
                log("✗ " + window + "ms 无首帧信号 → 重绑输出面（不动会话，手机无感知）");
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
                main.postDelayed(surfaceWatchdog, window);
                return;
            }

            log("✗ 重绑后仍无首帧 → 断开会话，+" + COLD_START_RE_ADVERTISE_DELAY_MS + "ms 重播");
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
            // ★ 0.3.17 看门狗互斥（A6）：只要协商已经走到 100%，首帧判定就归 4s 那条链管，
            //   这里立即让位。旧实现两条闸同时开火：先断会话、再重绑、再断 —— 一次故障被
            //   放大成 3 次重建，用户看到的就是"一直在重建"。
            if (tProgress100 > 0L) {
                log("· 起投看门狗让位：协商已到 100%，首帧由 4s 链负责");
                return;
            }
            log("✗ 起投 " + (CAST_STALL_TIMEOUT_MS / 1000) + "s 仍无画面"
                    + "（协商卡在中途，lastProgress=" + lastProgress + "）→ 断会话重播");
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
    /**
     * 秒断退役（0.3.12）。06:39 两次冷启动的日志实锤：第二场 progress 10 起投、
     * 700ms 被掐，但 logcat 显示当时 onCastVideoInitialized/onWfdSinkReady 都已到达
     * —— 手机在推流，这一刀纯属冤杀。「首场必不推流」的前提在空配落地后已全面失效。
     * 首屏判定全部交给 surfaceWatchdog（重绑优先）+ 12s 兜底。
     */
    private static final boolean EARLY_BAIL_ENABLED = false;
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

    /**
     * 真的进入 AOA accessory 模式了吗（0.3.15）。
     *
     * <p>与 {@link #isUsbAttached()} 的区别是「插着线」和「真在走数据线」：
     * 10/25 的全量验证里手机插着线但一直是 MTP/充电模式，
     * {@code getAccessoryList()} 恒为空、SDK 的 connectType 恒为 1（P2P）。
     * 凡是「USB 优先 / USB 专用超时窗口 / 角标通道」这类**会影响行为**的判断，
     * 都必须用这个更严格的判据。
     */
    private boolean isAoaAccessoryPresent() {
        return isAoaActive();
    }

    /**
     * 是否真的在走 AOA（0.3.18，D4）。
     *
     * <p>10/26 21:21 实测：手机已进入 accessory 模式（USB 列表里出现
     * <code>18D1:2D00</code>，SDK 侧 {@code UCar_AOASocketReadThread} 在跑），
     * 但 {@code UsbManager.getAccessoryList()} **恒为空** —— 只看它会把 USB 会话判成"非 AOA"，
     * 角标于是显示上一场的 P2P 频段。
     *
     * <p>三条证据任一成立即算 AOA：
     * <ol>
     *   <li>accessory 列表非空（标准路径）；</li>
     *   <li>存在 VID {@code 0x18D1}（Google）且 PID {@code 0x2D00/0x2D01} 的 UsbDevice
     *       —— 这就是 AOA 在本机的枚举形态；</li>
     *   <li>存在 {@code usb0} / {@code rndis0} 网络接口（AOA 数据面已起）。</li>
     * </ol>
     */
    private boolean isAoaActive() {
        try {
            android.hardware.usb.UsbManager um =
                    (android.hardware.usb.UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                android.hardware.usb.UsbAccessory[] acc = um.getAccessoryList();
                if (acc != null && acc.length > 0) return true;
                java.util.HashMap<String, android.hardware.usb.UsbDevice> devs = um.getDeviceList();
                if (devs != null) {
                    for (android.hardware.usb.UsbDevice d : devs.values()) {
                        if (d.getVendorId() == 0x18D1
                                && (d.getProductId() == 0x2D00 || d.getProductId() == 0x2D01)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n != null && (n.startsWith("usb0") || n.startsWith("rndis"))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        public void onConnectStateChanged(String deviceID, int state, int param) {
            if (deviceID != null && !"-1".equals(deviceID) && !deviceID.isEmpty()) {
                lastDeviceId = deviceID;
            }
            // ★ 0.3.17：真正按 SDK 的语义打日志 —— state 是状态码，param 是附加信息。
            //   旧写法把它当成 oldState -> newState，导致断开事件被当成"未知状态"漏掉。
            log("onConnectStateChanged dev=" + shortId(deviceID)
                    + " state=" + state + "(" + stateName(state) + ") param=" + param
                    + (param >= 10000 ? "(" + ReconnectBackoff.errorName(param) + ")" : ""));

            switch (state) {
                case ST_CONNECTED:
                case ST_CAST_CONNECTED: {
                    // 真连上：撤销所有待执行的重播/超时，握手结束，退避复位。
                    cancelReAdvertise();
                    main.removeCallbacks(castStopReconsiderRunnable);
                    cancelConnectingTimeout();
                    cancelNegotiationRescue();
                    negotiating = false;
                    sessionActive = true;
                    paused = false;
                    wirelessFailStreak = ReconnectBackoff.resetStreak();
                    castRebuildCount = 0;
                    decoderRebuildCount = 0;
                    logConnectRoundResult("成功");
                    // SDK 建链成功后会自己 stopAdvertise()，兜底广播必须同步停，
                    // 否则手机端会把同一台车当成"可连接的陌生设备"反复重扫。
                    if (fallbackAdv != null && fallbackAdv.isRunning()) {
                        log("会话已建立 → 停止兜底广播");
                        fallbackAdv.stop();
                    }
                    // 连上就刷新一次通道标签：顺带做 5G 事后校验（A9）。
                    resolveTransportLabel();
                    break;
                }

                case ST_CONNECT_FAILED: {
                    log("✗✗ 连接失败：错误码 " + param + "（" + ReconnectBackoff.errorName(param) + "）"
                            + "；中止于阶段 " + lastProgress + "%");
                    endSession("连接失败 code=" + param);
                    logConnectRoundResult("失败 code=" + param);
                    if (paused) {
                        log("（已停机，不再重播）");
                        break;
                    }
                    // 无线建链类失败 → 指数退避，给 P2P 组一个干净回收的窗口；
                    // 其它错误码与射频无关，固定 base 间隔即可。
                    wirelessFailStreak = ReconnectBackoff.streakAfterFailure(wirelessFailStreak, param);
                    boolean wireless = ReconnectBackoff.isWirelessBringupFailure(param);
                    long delay = ReconnectBackoff.nextDelayMs(wireless, wirelessFailStreak);
                    if (wireless && delay > ReconnectBackoff.BASE_MS) {
                        log("  ↳ 连续无线建链失败 " + wirelessFailStreak + " 次 → 退避 "
                                + delay + "ms 后重播，等 P2P 组干净回收");
                    }
                    scheduleReAdvertise(delay);
                    scheduleAutoRefresh();
                    break;
                }

                case ST_CAST_DISCONNECTED:
                    // param 恒为 -1（onWfdSinkStopped 不带原因），无法区分"抖动"与"用户断开"。
                    // 先挂 3 秒，等随后到达的 DISCONNECTED(1) 带上 param=11；等不到再按异常处理。
                    main.removeCallbacks(castStopReconsiderRunnable);
                    main.postDelayed(castStopReconsiderRunnable, 3000L);
                    armLinkLostIfNeeded(state);
                    break;

                case ST_DISCONNECTED: {
                    main.removeCallbacks(castStopReconsiderRunnable);
                    // SDK 初始化后可能先补一条"当前就是断开"的初始状态（param 常为 0）。
                    // 那时既没起投也没握过手，重播只会白白换掉刚下发的配对码。
                    if (!sessionActive && !castStarted && tStartCast == 0L) {
                        log("· 初始 DISCONNECTED（尚未建链），忽略");
                        break;
                    }
                    // ★ D1：param=11 有两种来源 —— 手机端用户断开，以及**我们自己**刚调用的
                    //   disconnect()。后者 3 秒内到达，必须按"本端断开"处理，否则看门狗每次
                    //   断开都会把车机送进停机态。
                    final boolean selfInitiated =
                            (System.currentTimeMillis() - selfDisconnectAt) < 3000L;
                    boolean byUser = (param == DISCONNECT_BY_USER) && !selfInitiated;
                    // ★ 0.3.19（A7）：停机中收到的这条回执，来源其实是「刚刚那次
                    //   userDisconnect() 的 disconnect()」，不是异常断连。
                    //   旧写法因为 selfInitiated 把 byUser 压成 false，于是打
                    //   「会话清理（异常断连）」+ 轮结果「异常断连」—— 用户手点的断开
                    //   在日志里变成了故障，复盘时会被误判。
                    final boolean stoppedByUs = paused;
                    log("会话断开（param=" + param + "，"
                            + (selfInitiated ? "本端断开回执" : byUser ? "用户/手机主动断开" : "异常断连") + "）");
                    // 先判僵死（要用 castStarted/everSurfaceReady），再清状态
                    armLinkLostIfNeeded(state);
                    if (stoppedByUs || byUser) {
                        endSession(stoppedByUs ? "停机中回执" : "用户主动断开");
                        logConnectRoundResult("用户断开");
                    } else {
                        endSession(selfInitiated ? "本端断开回执" : "异常断连");
                        logConnectRoundResult("异常断连");
                    }
                    if (byUser || stoppedByUs) {
                        // ★ 你的决定（0.3.17）：用户断开后保持停机，绝不自动连回去。
                        enterPaused(stoppedByUs ? "已停机（回执）" : "手机端主动断开");
                    } else if (selfInitiated) {
                        // 本端断开：调用方（看门狗/重播链）已经排好重播，这里不再叠加。
                        log("· 本端断开的回执，按已排程的重播继续");
                    } else {
                        scheduleReAdvertise(ReconnectBackoff.nextDelayMs(false, 0));
                        scheduleAutoRefresh();
                    }
                    break;
                }

                case ST_PHONE_GO_HOME:
                    log("手机回到桌面（画面保持最后一帧）");
                    break;

                case ST_PHONE_BACK_FOREGROUND:
                    log("手机回到前台");
                    break;

                default:
                    log("· 未处理的状态码 " + state + "（param=" + param + "），保持当前状态");
            }
            final int st = state;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            log("onConnectingProgress " + progress);
            main.post(() -> listener.onProgress(progress));
            if (progress > 0) {
                // ★ 0.3.17（A3）：一旦开始握手，就进入「禁止重播」状态。
                //   重播会换广播序列号（= 配对会话号），手机端会把这次握手当成另一个会话，
                //   正在进行的鉴权被作废 —— 这是"手机提示连接失败"的典型成因。
                if (!negotiating) {
                    negotiating = true;
                    armNegotiationRescue();
                    main.removeCallbacks(autoRefreshRunnable);
                }
                if (paused) {
                    log("■ 手机主动连入 → 自动退出停机态");
                    paused = false;
                    try { listener.onPaused(false); } catch (Throwable ignored) { }
                }
                main.removeCallbacks(castStopReconsiderRunnable);
                if (connectRoundStartMs == 0L) startConnectRound("收到进度 " + progress);
            }
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
            // ★ 0.3.15：触发条件补上「上一场根本没走到 progress 100」。
            //   旧条件只认 tProgress100 > 0，于是那些卡在 0/20/40 就死掉的场次
            //   从不清零，下场的 onSurfaceReady 会算出「距 progress100 3117331ms」
            //   这种 52 分钟的鬼数字（10/25 报告里成片出现），汇总也跟着失真。
            if (progress > 0 && progress <= 20 && (tProgress100 > 0L || lastProgress <= 0)) {
                // 新一轮会话，清掉上一轮的状态，避免汇总里串数据
                tProgress90 = 0L;
                tProgress100 = 0L;
                tSurfaceReadyAt = 0L;
                lastSegMs = -1L;
                lastFirstFrameMs = -1L;
                firstFrameFromStart = false;
                // D5：通道类型也必须按场重置，否则 dev=-1 的会话会沿用上一场的 P2P。
                stats.connectType = -1;
                fiveGLogged = false;
                // A4：本场还没判过通道 —— 允许 ct=-1 时先按兵不动，等 state=2 带回类型。
                transportResolved = false;
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
                if (isAoaAccessoryPresent()) {
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
                        // ★ 0.3.19（A6）：startCast 可能晚于 progress 100（投屏页还没起来），
                        //   此时 tStartCast 仍是 0 —— 直接相减会打出「1793145052541ms」这种
                        //   20 天的鬼数字，把整份报告的可信度拉低。
                        + "（startCast 至今 "
                        + (tStartCast == 0L ? "n/a（尚未起投）" : (now - tStartCast) + "ms") + "）");
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

        /**
         * 音频焦点（0.3.19 E3-3）。
         *
         * <p>0.3.18 及以前这是<b>空实现</b>，而且一行日志都没有 —— 完全不知道 SDK 有没有
         * 在等我们回应。ICCOA 的音频是 UCarAudioManager 从网络收包后直接写 AudioTrack，
         * 如果 SDK 在起播前先要一次焦点授权（{@code allowGainAudioFocus()}，
         * 反编译确认它是 public），而我们永不回应，SDK 只能走超时降级路径。
         *
         * <p>车机上不存在与它竞争的音频应用，所以这里无条件放行 —— 放行不会比现在更糟，
         * 但会消掉「等超时」这段不确定延迟。
         */
        @Override
        public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) {
            log("[音频] 焦点回调 type=" + type + " focus=" + focus
                    + (focus < 0 ? " → 丢失/释放，不抢占" : " → 放行"));
            // 0.3.20：focus < 0 是 LOSS/释放，这时候去 allowGain 反而是抢，
            // 断开瞬间那条 focus=-5 就是这类。只在真正的 GAIN 上放行。
            if (focus < 0) return;
            main.post(IccoaManager.this::allowGainAudioFocusForSdk);
        }

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
        // ★ 0.3.19（E5）：每次建配置都重算一次画布 —— 设置里改完分辨率不用重启 App，
        //   下一次连接就用新尺寸协商。以前只有 MainActivity 启动时算过一次。
        recomputeVideoSize();
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
            //   0.3.19（E1-3）：默认只声明 UNII-3（149~161）。
            //
            //   10/28 报告实测落在 5180MHz = ch36（UNII-1）。UNII-1 在多数地区的
            //   发射功率上限比 UNII-3 低 6~10dB，且常被限在 20MHz —— 空口余量和
            //   抗干扰都差一档。而「声音断续」正是空口延迟尖峰的表现。
            //   把 36~48 从白名单里去掉，逼手机在 UNII-3 上建组。
            //   个别只支持 UNII-1 的机型可以关掉这个开关（iccoa-5g-unii3-only=0）。
            final boolean unii3Only = IccoaPrefs.getBool(appCtx, "iccoa-5g-unii3-only", true);
            b.setAllowed5gChannels(unii3Only
                    ? new int[]{149, 153, 157, 161}
                    : new int[]{36, 40, 44, 48, 149, 153, 157, 161});
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
        final int maskedAction = e.getActionMasked();
        final long now = android.os.SystemClock.uptimeMillis();
        if (touch.shouldDrop(maskedAction, now)) {
            return;
        }

        final boolean fit = IccoaPrefs.getInt(appCtx, IccoaPrefs.KEY_TOUCH_FIT, 0) == 1;
        final int count = touch.map(e, viewW, viewH, videoW, videoH, fit);
        if (count <= 0) {
            return;
        }
        if (count >= 2) {
            // 双指缩放/旋转这类手势能不能用，先看事件有没有真的发出去。
            // 只记计数、不逐条打日志，避免把 UIBC 日志刷爆。
            stats.multiTouchEvents++;
            if (count > stats.multiTouchMax) {
                stats.multiTouchMax = count;
            }
        }

        // ★ 0.3.16：双指手势的 action 必须带 pointer index。
        //
        //   Android 里 ACTION_POINTER_DOWN / UP 的真实值是 5|(index<<8) / 6|(index<<8)，
        //   低 8 位之外那几位就是"第几根手指"。0.3.15 的 08:22 日志里 95 次双指事件
        //   全部"已发送"，但用户实测双指缩放仍然无效 —— 因为我们传的是 masked 值（5/6，
        //   index 恒为 0）：手机端解析成"第 0 根手指又按下一次"，第二根手指从未被登记，
        //   于是单击正常、pinch 永远不成立。
        //   现在改成带 index 的原始 action；若 SDK 拒收则自动退回 masked，保证不比原来差。
        final int rawAction = e.getAction();
        final boolean pointerEvent = maskedAction == android.view.MotionEvent.ACTION_POINTER_DOWN
                || maskedAction == android.view.MotionEvent.ACTION_POINTER_UP;
        final int wireAction = pointerEvent ? rawAction : maskedAction;

        try {
            boolean ok = adapter.sendTouchEvent(wireAction, count,
                    touch.idsTrimmed(), touch.xsTrimmed(), touch.ysTrimmed());
            if (!ok && pointerEvent && wireAction != maskedAction) {
                // 老协议只认 masked 值时，退回原行为（至少单击不受影响）。
                ok = adapter.sendTouchEvent(maskedAction, count,
                        touch.idsTrimmed(), touch.xsTrimmed(), touch.ysTrimmed());
                if (touch.takeLogSlot()) {
                    log("[UIBC] 带 index 的多指 action 被拒 → 退回 masked=" + maskedAction);
                }
            }
            // ★ 成功也要记一条。只在失败时打日志的话，「触摸没反应」时无从判断
            //   到底是事件没到、还是到了但被 SDK 拒、还是送到了手机但手机没执行。
            if (touch.takeLogSlot()) {
                log("触控回传 action=" + wireAction
                        + (pointerEvent ? "(多指,index=" + (rawAction >> 8) + ")" : "")
                        + " count=" + count
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
                boolean retry = adapter.sendTouchEvent(wireAction, count,
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
                + " | USB配件=" + (isAoaAccessoryPresent() ? "AOA已连接"
                        : isUsbAttached() ? "已插线(非AOA)" : "未连接"));
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

    // ==================================================================
    // 链路速率采样（0.3.16）
    // ==================================================================

    /**
     * 每 5 秒读一次 p2p0 的 rx/tx 字节数，折成 Mbps 记一行。
     *
     * <p>「5GHz 会卡」这种主观描述需要一条可对照的曲线：720p60 的码流通常在
     * 6~15 Mbps，如果采样值贴着这个上限反复见顶，卡顿就是带宽/空口问题（换信道、
     * 降帧率有效）；如果速率远低于码流却仍在卡，问题在解码或渲染，不在网络。
     * 采样本身只是两次 read，开销可忽略。
     */
    private static final long LINK_STATS_INTERVAL_MS = 5_000L;

    private long linkRxPrev = -1L, linkTxPrev = -1L, linkDropPrev = -1L, linkTsPrev = 0L;
    private int linkTick = 0;
    private boolean linkStatsWarned = false;

    private final Runnable linkStatsTick = new Runnable() {
        @Override
        public void run() {
            if (!castStarted) return;
            long now = System.currentTimeMillis();
            long[] rx = readIfaceStat("rx_bytes");
            long[] tx = readIfaceStat("tx_bytes");
            long[] drop = readIfaceStat("rx_dropped");
            if (rx == null || tx == null) {
                // ★ 0.3.19（E3-1）：0.3.16 起这条采样一直静默失败 ——
                //   /sys/class/net/p2p0/statistics/* 对普通 App 不可读，readNetStat 返回 -1，
                //   外层 if 不成立就跳过，**一行日志都不留**。10/28 报告 1227 行里
                //   「[链路]」出现 0 次，就是这个原因。
                //   现在读不到就明说一次，别再让「没数据」冒充「没异常」。
                if (!linkStatsWarned) {
                    linkStatsWarned = true;
                    log("[链路] 采样不可用：/sys 与 /proc/net/dev 都读不到 p2p0 —— 速率/丢包本场缺席");
                }
            } else if (linkRxPrev >= 0 && linkTsPrev > 0) {
                double sec = (now - linkTsPrev) / 1000.0;
                if (sec > 0.5) {
                    double rxMbps = (rx[0] - linkRxPrev) * 8 / sec / 1_000_000.0;
                    double txMbps = (tx[0] - linkTxPrev) * 8 / sec / 1_000_000.0;
                    long dDrop = (drop != null && linkDropPrev >= 0) ? (drop[0] - linkDropPrev) : -1L;
                    // 每 10 秒记一行，避免把日志刷成流量计
                    if ((linkTick++ & 1) == 0) {
                        log(String.format(java.util.Locale.US,
                                "[链路] %s 收 %.1f Mbps / 发 %.1f Mbps / 新增丢包 %s%s",
                                rx[1] == 0L ? "p2p0" : rx[1] == 1L ? "wlan0" : "全机",
                                rxMbps, txMbps, dDrop < 0 ? "n/a" : String.valueOf(dDrop), wifiRadio()));
                    }
                }
            }
            if (rx != null) linkRxPrev = rx[0];
            if (tx != null) linkTxPrev = tx[0];
            if (drop != null) linkDropPrev = drop[0];
            linkTsPrev = now;
            main.postDelayed(this, LINK_STATS_INTERVAL_MS);
        }
    };

    /**
     * 射频侧的三个数（0.3.19 E3-4）：协商速率 / 信号 / 频点。
     *
     * <p>「声音断续」如果伴随 RSSI &lt; -70dBm 或协商速率掉到 20~40Mbps，
     * 那就是空口余量不足；如果三者都健康却仍在断续，问题就在协议栈或调度，
     * 和射频无关 —— 这条区分决定了下一步要不要换信道/换模式。
     *
     * <p>注意：{@code getConnectionInfo()} 报的是 STA 侧（wlan0）的连接，
     * P2P 走的是 p2p0，未必能读到。读不到就打 n/a，不猜。
     */
    private String wifiRadio() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return " / 射频 n/a";
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return " / 射频 n/a";
            int rssi = wi.getRssi();
            int speed = wi.getLinkSpeed();
            int freq = wi.getFrequency();
            if (rssi == -127 && speed <= 0 && freq <= 0) return " / 射频 n/a";
            return " / 射频 " + rssi + "dBm " + speed + "Mbps " + freq + "MHz";
        } catch (Throwable t) {
            return " / 射频 n/a";
        }
    }

    /**
     * 读某个接口的计数器（0.3.19 E3-1 重写）。
     *
     * <p>返回 {@code {值, 来源}}，来源 0=sysfs / 1=/proc/net/dev；读不到返回 null。
     *
     * <p>原来的实现只看 sysfs。Android 10 上 untrusted_app 域没有
     * {@code /sys/class/net/*} 的读权限，所以它在真机上从来返回 -1 —— 采样器静默空转。
     * {@code /proc/net/dev} 是另一条路，权限模型不同，多数机型可读。
     */
    private long[] readIfaceStat(String field) {
        // ① sysfs：最准，但常被 SELinux 挡
        for (int i = 0; i < 2; i++) {
            String iface = (i == 0) ? "p2p0" : "wlan0";
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                        "/sys/class/net/" + iface + "/statistics/" + field));
                String line = r.readLine();
                r.close();
                if (line != null) return new long[]{Long.parseLong(line.trim()), i};
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        // ② /proc/net/dev：列顺序固定，按接口名找行
        final int col;
        switch (field) {
            case "rx_bytes":   col = 0;  break;
            case "rx_dropped": col = 3;  break;
            case "tx_bytes":   col = 8;  break;
            default: return null;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/dev"));
            String line;
            while ((line = r.readLine()) != null) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String name = line.substring(0, c).trim();
                if (!"p2p0".equals(name) && !"wlan0".equals(name)) continue;
                String[] parts = line.substring(c + 1).trim().split("\\s+");
                if (parts.length <= col) continue;
                long v = Long.parseLong(parts[col]);
                r.close();
                return new long[]{v, "p2p0".equals(name) ? 0L : 1L};            }
            r.close();
        } catch (Throwable ignored) {
            // 两条路都不通 → 再试 TrafficStats
        }
        // ③ TrafficStats：全机计数，不区分接口。
        //   0.3.19 试过 /proc/net/dev，本机同样读不到（SELinux 挡 untrusted_app）——
        //   日志里就是那句「采样不可用」。这条路一定能拿到，代价是「全机流量」而不是
        //   「p2p0 流量」。车机在投屏时基本只有这一条流，用来判断「数据有没有在动、
        //   速率大概多少、有没有长时间归零」够用。丢包拿不到，返回 null 即可。
        try {
            long v = -1L;
            if ("rx_bytes".equals(field)) {
                v = android.net.TrafficStats.getTotalRxBytes();
            } else if ("tx_bytes".equals(field)) {
                v = android.net.TrafficStats.getTotalTxBytes();
            }
            if (v > 0L) return new long[]{v, 2};
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 采样来源的可读名（0=p2p0 / 1=wlan0 / 2=全机合计）。 */
    private String ifaceName(long idx) {
        return idx == 0L ? "p2p0" : idx == 1L ? "wlan0" : "全机";
    }

    private void startLinkStats() {
        main.removeCallbacks(linkStatsTick);
        linkRxPrev = -1L;
        linkTxPrev = -1L;
        linkTsPrev = 0L;
        linkTick = 0;
        main.postDelayed(linkStatsTick, LINK_STATS_INTERVAL_MS);
    }

    private void stopLinkStats() {
        main.removeCallbacks(linkStatsTick);
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

    /**
     * SDK 状态码 → 可读名（0.3.17 修正）。
     *
     * <p>旧映射是自己猜的（1=CONNECTING / 2=CONNECTED / 3=ESTABLISHED / 0=DISCONNECTED），
     * 与 SDK 的 {@code UCarConnectState} 对不上，日志因此一直在误导排查方向。
     */
    private static String stateName(int s) {
        switch (s) {
            case ST_DISCONNECTED: return "DISCONNECTED";
            case ST_CONNECTED: return "CONNECTED";
            case ST_CONNECT_FAILED: return "CONNECT_FAILED";
            case ST_CAST_CONNECTED: return "CAST_CONNECTED";
            case ST_CAST_DISCONNECTED: return "CAST_DISCONNECTED";
            case ST_PHONE_GO_HOME: return "PHONE_GO_HOME";
            case ST_PHONE_BACK_FOREGROUND: return "PHONE_BACK_FOREGROUND";
            default: return "STATE_" + s;
        }
    }
}
