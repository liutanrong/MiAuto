package com.miauto.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/**
 * 兜底 BLE 广播器 —— 为低端/老车机准备。
 *
 * <p><b>为什么要它</b>：ICCOA SDK 内部用 {@code BluetoothLeAdvertiser.startAdvertisingSet()}
 * （扩展广播 API）发广播，并对 {@code AdvertisingSetParameters.Builder.mInterval} 做反射改写。
 * 部分安卓 9 及以下车机的蓝牙芯片/协议栈不支持该路径（或厂商实现有 bug），结果是
 * <b>调用返回成功、车机端一切正常（配对码照常显示），但射频层面没有任何广播发出</b>，
 * 手机 CarWith 因此永远发现不了车机。
 *
 * <p><b>它做什么</b>：用最传统的 31 字节广播 API（{@code startAdvertising}）复刻 SDK 的广播帧——
 * 服务 UUID {@code 0xFCFB} + 服务数据 {@code 0x0001/0x0002}，字段布局逐字节对齐
 * {@code com.share.connect.ble.BleAdvertisementBuilder}。两条广播并行发出，手机的 GATT 连接
 * 仍由 SDK 自己的 GATT Server 处理，因此不影响后续配对。
 *
 * <p><b>诊断价值</b>：{@link AdvertiseCallback} 会把真实的失败错误码打到车机日志上
 * （TOO_MANY_ADVERTISERS / DATA_TOO_LARGE / FEATURE_UNSUPPORTED 等），
 * 这是判断一台车机蓝牙能力最直接的证据。
 */
final class FallbackBleAdvertiser {

    private static final String TAG = "MiAuto/FallbackBle";

    // ---- 与 SDK 逐字节对齐的广播参数（见 BleAdvertisementBuilder）----
    private static final ParcelUuid ADV_SERVICE_UUID =
            ParcelUuid.fromString("0000FCFB-0000-1000-8000-00805F9B34FB");
    private static final ParcelUuid CAR_INFO_1_UUID =
            ParcelUuid.fromString("00000001-0000-1000-8000-00805F9B34FB");
    private static final ParcelUuid CAR_INFO_2_UUID =
            ParcelUuid.fromString("00000002-0000-1000-8000-00805F9B34FB");
    /** SDK 侧硬编码：UCarConnectProxy.bindConnectService() 里 putExtra(PROTOCOL_VERSION, "1.5")。 */
    private static final String PROTOCOL_VERSION = "1.5";
    /** UCarConfig.DEFAULT_ADVERTISING_RSSI_THRESHOLD。 */
    private static final int RSSI_THRESHOLD = -60;
    /** UCarConnectProxy.DEFAULT_CUSTOM_DATA。 */
    private static final String DEFAULT_VENDOR_DATA = "0000";
    private static final int CAR_ID_LENGTH = 6;
    private static final int CAR_NAME_LENGTH = 16;

    /** 回调宿主：把日志接到 IccoaManager 的日志通道上。 */
    interface Host {
        void log(String msg);
    }

    private final Context ctx;
    private final Host host;

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;
    private boolean running = false;

    /** 是否已因「数据过长」退化为只发主广播（不带扫描响应）。 */
    private boolean droppedScanResponse = false;

    /** 是否已因「广告位被占」做过一次延时重试。 */
    private boolean retriedAfterBusy = false;

    /** SDK 广播序列号落地位置（见 SDK 的 BleAdvertisementBuilder.SERIAL_NUM_PREFS）。 */
    private static final String SDK_SERIAL_PREFS = "serial_num_prefs";
    private static final String SDK_SERIAL_KEY = "last_serial_num";

    /**
     * 序列号 —— 必须与 SDK 当前用的那一个完全一致。
     *
     * <p>在 ICCOA 协议里序列号就是「配对会话号」：变了，手机端才认为这是一次全新会话并重新
     * 弹配对窗。<b>两条广播各用各的序列号，手机端会把同一台车看成两个会话，在握手过程中反复
     * 重置配对，表现就是「手机端总是提示连接失败」</b>。所以这里不再自行自增，而是读取 SDK
     * 写入 {@code serial_num_prefs} 的值来镜像（{@link #readSdkSerial()}）。
     *
     * <p>字段初值仅在「SDK 还没广播过、读不到」时使用（首次启动的极短窗口）。
     */
    private byte serialNum = (byte) new Random().nextInt(0xFF);

    /** 静态明细（ABI/BLE 能力）是否已记录过；只在首次、序列号变化、5 分钟例报时重打。 */
    private boolean staticInfoLogged = false;
    private long lastDetailLogAtMs = 0L;

    /** 明细日志的最小间隔（无变化时）。 */
    private static final long DETAIL_LOG_THROTTLE_MS = 300_000L;

    FallbackBleAdvertiser(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

    /**
     * 启动兜底广播。
     *
     * @param carId 6 字节车机 ID（与 UCarConfig.setCarBrMac 用的是同一份）。
     * @param sessionActive 当前是否已有投屏会话。空转期（false）SDK 每 30 秒重播一次、
     *   序列号与配对码每次都变，明细若跟着变就等于没降噪 —— 空转期只做 5 分钟例报；
     *   会话期内每次启动都是关键事件，照常打明细。
     * @return true 表示启动调用已提交（真正是否发出见 AdvertiseCallback 的日志）。
     */
    boolean start(byte[] carId, boolean sessionActive) {
        stop();
        // 每次启动重置自救标志：本轮的失败要用本轮的条件重试。
        droppedScanResponse = false;
        retriedAfterBusy = false;

        if (carId == null || carId.length != CAR_ID_LENGTH) {
            log("✗ 兜底广播跳过：carId 非法");
            return false;
        }

        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("✗ 兜底广播跳过：蓝牙未开启");
            return false;
        }

        // 必须每次显式获取：stop() 会把字段置 null，而本类里没有任何其他赋值点 ——
        // 漏掉这一步时 startAdvertising() 永远落在 null 上（NPE，日志里表现为
        // 「兜底广播异常（可能缺 BLUETOOTH_ADVERTISE 权限）」）。注意 advertiser 为 null
        // 还有另一种原因：API 31+ 未授予 BLUETOOTH_ADVERTISE 运行时权限时本方法就返回 null。
        BluetoothLeAdvertiser le = adapter.getBluetoothLeAdvertiser();
        if (le == null) {
            log("✗ 兜底广播跳过：BluetoothLeAdvertiser 不可用（未授予 BLUETOOTH_ADVERTISE 或芯片不支持 BLE 广播）");
            return false;
        }
        advertiser = le;

        CarCert cert = readCert();
        Integer sdkSerial = readSdkSerial();
        byte newSerial = serialNum;
        if (sdkSerial != null) {
            newSerial = (byte) sdkSerial.intValue();
        }
        // 明细只在三种情形打：进程首次、会话期内（重连是关键事件）、空转期满 5 分钟。
        // serial 每次都变（SDK 重播就换号），拿它当触发条件等于没降噪。
        boolean detail = !staticInfoLogged || sessionActive ||
                SystemClock.uptimeMillis() - lastDetailLogAtMs > DETAIL_LOG_THROTTLE_MS;
        if (detail) {
            lastDetailLogAtMs = SystemClock.uptimeMillis();
            staticInfoLogged = true;
            log("车机信息：ABI=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            log("BLE 能力：multipleAdv=" + adapter.isMultipleAdvertisementSupported()
                    + " extendedAdv=" + adapter.isLeExtendedAdvertisingSupported()
                    + " offloadedFilter=" + adapter.isOffloadedFilteringSupported());
            if (sdkSerial != null) {
                serialNum = newSerial;
                log("序列号镜像 SDK：serial=" + (serialNum & 0xFF) + "（与 SDK 广播保持同一配对会话号）");
            } else {
                log("! 读不到 SDK 序列号，沿用本地值 serial=" + (serialNum & 0xFF)
                        + "（两条广播序列号不一致时手机会当成两个会话）");
            }
        } else {
            if (sdkSerial != null) serialNum = newSerial;
            log("兜底广播重启（serial=" + (serialNum & 0xFF) + "；空转重播，明细略）");
        }

        // 车机名必须与 SDK 广播出去的那一个一致，否则手机端会把同一台车当成两个设备。
        String carName = resolveCarName(cert);

        AdvertiseData advData;
        AdvertiseData scanResponse;
        try {
            advData = buildAdvData(carId, cert);
            scanResponse = buildScanResponse(carName);
        } catch (Throwable t) {
            log("✗ 兜底广播数据构造失败: " + t);
            return false;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();

        callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                running = true;
                log("✓ 兜底广播已启动（传统广播）：mode="
                        + (settingsInEffect == null ? "?" : settingsInEffect.getMode())
                        + " tx=" + (settingsInEffect == null ? "?" : settingsInEffect.getTxPowerLevel())
                        + " serial=" + (serialNum & 0xFF)
                        + (droppedScanResponse ? "（已去掉扫描响应）" : ""));
            }

            @Override
            public void onStartFailure(int errorCode) {
                running = false;
                log("✗ 兜底广播启动失败：错误码 " + errorCode + "（" + describe(errorCode) + "）");
                // 错误码 1 DATA_TOO_LARGE：老蓝牙栈装不下「主广播 + 扫描响应」两段 31 字节数据。
                // 去掉扫描响应（只丢车机名，发现靠的是主广播里的 carId）再试一次。
                if (errorCode == 1 && scanResponse != null && !droppedScanResponse) {
                    droppedScanResponse = true;
                    log("→ 数据过长，改为「只发主广播、不带扫描响应」重试（车机名不再广播，发现不受影响）");
                    try {
                        advertiser.startAdvertising(settings, advData, callback);
                        return;
                    } catch (Throwable t) {
                        log("✗ 去扫描响应重试抛异常: " + t);
                    }
                }
                // 错误码 2 TOO_MANY_ADVERTISERS：广告位被系统蓝牙/车机自带互联占用，延时抢一次。
                if (errorCode == 2 && !retriedAfterBusy) {
                    retriedAfterBusy = true;
                    log("→ 广告位被占，3 秒后自动重试一次（根治办法：关掉车机自带的 CarLife / HiCar 互联）");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            advertiser.startAdvertising(settings, advData, scanResponse, callback);
                        } catch (Throwable t) {
                            log("✗ 广告位重试抛异常: " + t);
                        }
                    }, 3000L);
                }
            }
        };

        try {
            advertiser.startAdvertising(settings, advData, scanResponse, callback);
        } catch (Throwable t) {
            log("✗ 兜底广播异常（可能缺 BLUETOOTH_ADVERTISE 权限）: " + t);
            return false;
        }
        log("→ 兜底广播已提交（等服务端回调确认，日志随后给出真实结果）");
        return true;
    }

    void stop() {
        BluetoothLeAdvertiser a = advertiser;
        AdvertiseCallback cb = callback;
        advertiser = null;
        callback = null;
        running = false;
        if (a == null || cb == null) return;
        try {
            a.stopAdvertising(cb);
            log("兜底广播已停止");
        } catch (Throwable t) {
            log("stopAdvertising 异常: " + t);
        }
    }

    // ==================================================================
    // 广播帧构造（逐字节对齐 SDK 的 BleAdvertisementBuilder）
    // ==================================================================

    /**
     * 广播数据：Service UUID 0xFCFB + ServiceData 0x0001。
     *
     * <pre>
     * 0x0001 服务数据布局（共 18 字节，见 BleAdvertisementBuilder.buildAdvertisingData）：
     *   [0..1] 协议版本 "1.5" → 0x01 0x05
     *   [2]    序列号（每次重播自增）
     *   [3..8] carId（6 字节）
     *   [9..12] modelId = vendor_id + product_id（4 字节）
     *   [13..14] vendorData（2 字节，默认 0000）
     *   [15]   RSSI 阈值（-60 → 0xC4）
     * （仅在重连已知手机时追加 2 字节手机蓝牙 MAC 的 CRC16；首次发现不带。）
     * </pre>
     */
    private AdvertiseData buildAdvData(byte[] carId, CarCert cert) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(18);
        out.write(0x01);                    // "1.5" → major
        out.write(0x05);                    //        → minor
        out.write(serialNum);               // 序列号
        out.write(carId, 0, CAR_ID_LENGTH); // carId
        out.write(hexToBytes(cert.modelId, 4));   // modelId
        out.write(hexToBytes(cert.vendorData, 2)); // vendorData
        out.write((byte) RSSI_THRESHOLD);   // RSSI 阈值

        return new AdvertiseData.Builder()
                .addServiceUuid(ADV_SERVICE_UUID)
                .addServiceData(CAR_INFO_1_UUID, out.toByteArray())
                .setIncludeDeviceName(false)
                .build();
    }

    /** 扫描响应：ServiceData 0x0002 携带车机名（16 字节，UTF-8，超长截断并补 …）。 */
    private AdvertiseData buildScanResponse(String carName) {
        return new AdvertiseData.Builder()
                .addServiceData(CAR_INFO_2_UUID, encodeCarName(carName))
                .build();
    }

    /** 复刻 SDK 的 encodeCarName：超 16 字节时逐字截断并追加省略号，最后补零到 16 字节。 */
    private static byte[] encodeCarName(String name) {
        if (name == null) name = "";
        byte[] ret = name.getBytes(StandardCharsets.UTF_8);
        while (ret.length > CAR_NAME_LENGTH && name.length() > 0) {
            name = name.substring(0, name.length() - 1);
            ret = (name + "…").getBytes(StandardCharsets.UTF_8);
        }
        byte[] out = new byte[CAR_NAME_LENGTH];
        System.arraycopy(ret, 0, out, 0, Math.min(ret.length, CAR_NAME_LENGTH));
        return out;
    }

    // ==================================================================
    // 车机名解析（必须与 SDK 广播出来的那一个逐字节一致）
    // ==================================================================

    /** SDK 证书文件（UCarConfig.setCcdFilePath 的值）。 */
    private static final String CCD_FILE = "ccd.data";

    /**
     * 车机名 —— 走与 SDK 完全相同的取值链，拿到的就是射频上真正广播的名字。
     *
     * <p>SDK 侧链路：{@code UCarConnectProxy.readCarName()} →
     * {@code new CarVerifier(context, "ccd.data").getDevice().getShortName()}，
     * 而上游 MiAuto 的 SDK 构建把 {@code Device.getShortName()} / {@code getPName()} 改成了常量
     * {@code "MiAutoCar"}（SDK 日志里 {@code carName: MiAutoCar} 即此值）。
     *
     * <p><b>为什么不能直接用证书的 {@code short_name}</b>：ccd.data 是 demo 证书，其
     * {@code short_name} 为 {@code 测0试1车2机3}，与 SDK 实际广播的名字不同。而兜底广播恰恰是
     * <b>低端车机（SDK 扩展广播发不出去）唯一的发现路径</b>，名字不一致会让手机端把同一台车
     * 显示成两个设备、甚至认成陌生设备重新走一遍配对。
     *
     * <p>反射是安全的：{@code icar-sdk.dex} 是打包完成后注入的，R8 看不到它的实现，
     * 因此 SDK 的类名/方法名不会被混淆。三条退路依次为
     * SDK 取值链 → 证书 short_name → 常量 {@code MiAutoCar}。
     */
    private String resolveCarName(CarCert cert) {
        try {
            Object device = sdkDevice();
            if (device != null) {
                Object name = device.getClass().getMethod("getShortName").invoke(device);
                if (name instanceof String && !((String) name).isEmpty()) {
                    log("车机名 = \"" + name + "\"（取自 SDK Device.getShortName()，与 SDK 广播一致）");
                    return (String) name;
                }
            }
            log("! SDK Device.getShortName() 返回空");
        } catch (Throwable t) {
            log("! 反射读 SDK 车机名失败：" + t);
        }
        log("! 回退证书 short_name=\"" + cert.shortName + "\""
                + "（若与 SDK 广播不一致，手机会把同一台车当成两个设备）");
        return cert.shortName;
    }

    /**
     * 复刻 {@code UCarConnectProxy.readCarName()} 的设备获取方式：
     * CarVerifier 解析 ccd.data 得到 Device；拿不到时退回 {@code Device.UNKNOWN_CAR}。
     */
    private Object sdkDevice() throws Exception {
        Class<?> verifierCls = Class.forName("com.share.connect.utils.CarVerifier");
        Object verifier = verifierCls
                .getConstructor(Context.class, String.class)
                .newInstance(ctx, CCD_FILE);
        Object device = verifierCls.getMethod("getDevice").invoke(verifier);
        if (device != null) return device;
        Class<?> deviceCls = Class.forName("com.share.connect.Device");
        return deviceCls.getField("UNKNOWN_CAR").get(null);
    }

    private static byte[] hexToBytes(String hex, int expected) {
        if (hex == null) hex = "";
        String s = hex.replaceAll("[^0-9a-fA-F]", "");
        byte[] raw = new byte[s.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] out = new byte[expected];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, expected));
        return out;
    }

    // ==================================================================
    // 证书读取（ccd.data 的 vid/pid/short_name —— modelId 与车机名都来自这里）
    // ==================================================================

    private static final class CarCert {
        String modelId = "00010001";     // vendor_id + product_id
        String vendorData = DEFAULT_VENDOR_DATA;
        String shortName = "MiAutoCar";
    }

    private CarCert readCert() {
        CarCert cert = new CarCert();
        try (InputStream in = ctx.getAssets().open("ccd.data")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);

            // ccd.data 是两层编码：外层 base64，内层 JSON
            //   {"content": [有符号字节数组], "cryptoConfigIndex": ..., "signature": ...}
            // 其中 content 还原成 UTF-8 后才是真正的证书 JSON（含 short_name / vendor_id 等）。
            String b64 = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
            byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            JSONObject outer = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            JSONArray arr = outer.getJSONArray("content");
            byte[] raw = new byte[arr.length()];
            for (int i = 0; i < arr.length(); i++) raw[i] = (byte) arr.getInt(i);
            JSONObject inner = new JSONObject(new String(raw, StandardCharsets.UTF_8));

            String vid = inner.optString("vendor_id", "0001");
            String pid = inner.optString("product_id", "0001");
            cert.modelId = (vid + pid).toLowerCase();
            String shortName = inner.optString("short_name", "");
            if (!shortName.isEmpty()) cert.shortName = shortName;
            log("证书：modelId=" + cert.modelId + " 证书short_name=\"" + cert.shortName + "\""
                    + " vendor=" + inner.optString("vendor_name", "?")
                    + "（广播用的车机名另由 resolveCarName() 对齐 SDK）");
        } catch (Throwable t) {
            log("! 读取 ccd.data 失败，改用默认值：modelId=" + cert.modelId
                    + " 车机名=\"" + cert.shortName + "\"（" + t + "）");
        }
        return cert;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    /**
     * 读取 SDK 当前广播用的序列号。
     *
     * <p>SDK 的 {@code BleAdvertisementBuilder.generateSerialNumber()} 每次构建广播数据都会把
     * 当前值写进 {@code serial_num_prefs/last_serial_num}。本方法必须在 SDK 的
     * {@code startAdvertise()} 之后调用（调用方已延后 800ms），否则读到的是上一轮的值。
     *
     * @return 序列号（0..255），读不到时返回 null
     */
    private Integer readSdkSerial() {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(SDK_SERIAL_PREFS, Context.MODE_PRIVATE);
            if (!sp.contains(SDK_SERIAL_KEY)) return null;
            return sp.getInt(SDK_SERIAL_KEY, 0) & 0xFF;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(int code) {
        switch (code) {
            case 1: return "数据过长 DATA_TOO_LARGE";
            case 2: return "广告位已满 TOO_MANY_ADVERTISERS —— 该芯片广告位数量有限，已被系统蓝牙或其他应用占用";
            case 3: return "已启动 ALREADY_STARTED";
            case 4: return "内部错误 INTERNAL_ERROR";
            case 5: return "芯片不支持 FEATURE_UNSUPPORTED —— 该车机蓝牙不支持 BLE 广播";
            default: return "未知错误";
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try {
            host.log("[兜底广播] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
