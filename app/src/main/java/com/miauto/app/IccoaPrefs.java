package com.miauto.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ICCOA 相关的设置键。
 *
 * <p>这些键原先以字符串字面量的形式散落在 {@code IccoaManager} 和 Kotlin 设置页里，
 * 拼错一个字母不会报错，只会静默回退到默认值 —— 排查起来极其费劲（"为什么改了设置没生效"
 * 最后往往是键名不一致）。集中到这里，Java 侧和设置页共用同一份常量。
 *
 * <p>注意：Kotlin 侧（{@code SettingsFragment} / {@code ConnectionSetupFragment}）里的键名
 * 也要跟着这里改，否则会出现「设置页写进去了、运行时读不到」。
 */
public final class IccoaPrefs {

    private IccoaPrefs() {
    }

    /** 设置文件。全项目共用一份，键名必须唯一。 */
    public static final String FILE = "settings";

    /** 无线通道。0 = Wi-Fi Direct（P2P），1 = 车机热点（SoftAP）。 */
    public static final String KEY_TRANSPORT = "native-ap-transport";

    /** 编码帧率上限，15..60。 */
    public static final String KEY_FPS_LIMIT = "fps-limit";

    /**
     * 触控坐标映射方式。
     *
     * <p>{@code 0} = 拉伸（默认）：SDK 把 1280×592 的画面铺满整个 SurfaceView，
     * 因此按 View 尺寸线性映射就对。
     * <p>{@code 1} = 等比：画面按比例缩放并居中、四周留黑边，此时必须扣掉黑边再映射，
     * 否则边缘区域会整体偏移。
     */
    public static final String KEY_TOUCH_FIT = "touch-fit-mode";

    /** P2 车联能力总开关：方控 / 日夜 / 车况回传。默认开。 */
    public static final String KEY_CAR_LINK = "iccoa-carlink";

    /** GPS 回传（把车机定位发给手机，手机导航可直接用）。默认开。 */
    public static final String KEY_GPS_UPLINK = "iccoa-gps-uplink";

    public static int getInt(Context ctx, String key, int def) {
        try {
            return prefs(ctx).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        try {
            return prefs(ctx).getInt(key, def ? 1 : 0) != 0;
        } catch (Throwable t) {
            return def;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
