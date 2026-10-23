package com.miauto.app;

import android.location.Location;

import java.lang.reflect.Constructor;
import android.view.KeyEvent;

import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;

/**
 * SDK 里「车机 → 手机」那半边能力的最小封装。
 *
 * <p>这些 API 一直存在、但上游从未调用过（详见 {@code docs/ICCOA-RUNTIME-FINDINGS.md} §12）。
 * 集中在这里的原因有两条：
 * <ol>
 *   <li>每个调用都要判空 + 捕获异常 —— SDK 内部状态不对时可能直接抛，不能让它冒到 UI 线程；</li>
 *   <li>枚举常量在 SDK 版本之间可能增减，编译期绑定比散落各处更好排查。</li>
 * </ol>
 *
 * <p>所有方法都返回「是否真的发出去了」，调用方据此决定要不要记日志。
 */
final class IccoaCarLink {

    private IccoaCarLink() {
    }

    // ==================================================================
    // 方控：Android KeyEvent -> ICCOA KeyCodeType
    // ==================================================================

    /**
     * 把 Android 的按键码映射成 SDK 的按键码。
     *
     * <p>车机上真正会用到的是这几组：方向键（旋钮/方向盘）、返回、主页、媒体键、电话键。
     * 音量键**不映射** —— 车机自己会处理音量，透传给手机反而会出现双份调节。
     *
     * @return 无法映射时返回 {@code null}
     */
    static UCarCommon.KeyCodeType mapKeyCode(int keyCode, int repeatCount) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return UCarCommon.KeyCodeType.KEY_CODE_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return UCarCommon.KeyCodeType.KEY_CODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return UCarCommon.KeyCodeType.KEY_CODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return UCarCommon.KeyCodeType.KEY_CODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return UCarCommon.KeyCodeType.KEY_CODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return UCarCommon.KeyCodeType.KEY_CODE_ENTER;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return UCarCommon.KeyCodeType.KEY_CODE_BACK;
            case KeyEvent.KEYCODE_HOME:
                return UCarCommon.KeyCodeType.KEY_CODE_MAIN;
            case KeyEvent.KEYCODE_MENU:
                return UCarCommon.KeyCodeType.KEY_CODE_MEDIA;
            case KeyEvent.KEYCODE_TAB:
                return repeatCount > 0
                        ? UCarCommon.KeyCodeType.KEY_CODE_NEXT_FOCUS
                        : UCarCommon.KeyCodeType.KEY_CODE_NEXT_FOCUS;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS;
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_CALL:
                return UCarCommon.KeyCodeType.KEY_CODE_CALL;
            case KeyEvent.KEYCODE_ENDCALL:
                return UCarCommon.KeyCodeType.KEY_CODE_ENDCALL;
            case KeyEvent.KEYCODE_PAGE_UP:
                return UCarCommon.KeyCodeType.KEY_CODE_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return UCarCommon.KeyCodeType.KEY_CODE_PAGE_DOWN;
            default:
                return null;
        }
    }

    /**
     * 转发一次按键。
     *
     * <p>播放/暂停键要拆成两条：ICCOA 没有「切换」语义，按下发 PLAY、抬起发 PAUSE
     * 会让手机端每次点击都被解释成"播放后又暂停"。所以这里只在**按下**时发一次，
     * 由对端自己切换。
     */
    static boolean sendKey(UCarAdapter adapter, int keyCode, int action, int repeatCount) {
        if (adapter == null) {
            return false;
        }
        UCarCommon.KeyCodeType code = mapKeyCode(keyCode, repeatCount);
        if (code == null) {
            return false;
        }
        UCarCommon.KeyEventActionType type;
        if (action == KeyEvent.ACTION_DOWN) {
            type = repeatCount > 0
                    ? UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_PRESS
                    : UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_DOWN;
        } else if (action == KeyEvent.ACTION_UP) {
            type = UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_UP;
        } else {
            return false;
        }
        try {
            return adapter.sendKeyEvent(type, code, repeatCount);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 日夜模式
    // ==================================================================

    static boolean sendDayNight(UCarAdapter adapter, boolean night) {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.notifySwitchDayOrNight(night
                    ? UCarCommon.DayNightMode.NIGHT_MODE
                    : UCarCommon.DayNightMode.DAY_MODE);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 电话
    // ==================================================================

    static boolean answerCall(UCarAdapter adapter) {
        try {
            return adapter != null && adapter.notifyPickUpCall();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hangUpCall(UCarAdapter adapter) {
        try {
            return adapter != null && adapter.notifyHungUpCall();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 车况：GPS
    // ==================================================================

    /** 反序列化好的 GPSInfo 构造方式，第一次调用时解析一次。 */
    private static java.lang.reflect.Constructor<?> gpsCtor;
    private static java.lang.reflect.Field[] gpsFields;
    private static boolean gpsReflectFailed = false;

    /**
     * 把车机定位发给手机。
     *
     * <p>为什么用反射：{@code GPSInfo} 在运行期只有**一个十参构造函数**，没有 setter
     * （反编译确认，参数顺序由字节码里的 {@code iput} 顺序决定：
     * {@code altitude, latitude, longitude, antennaState, pDop, speed, heading, satsUsed, satsVisible, timeStamp}）。
     * 而编译期用的 {@code icar-sdk-stubs.jar} 是裁剪过的签名存根，里面只有无参构造 ——
     * 直接 {@code new GPSInfo(...)} 编译不过，只能运行期反射。
     *
     * <p>反射失败不抛：定位是增值功能，发不出去就当没有，绝不能影响投屏主链路。
     */
    static boolean sendGps(UCarAdapter adapter, Location loc) {
        if (adapter == null || loc == null || gpsReflectFailed) {
            return false;
        }
        try {
            if (gpsCtor == null && gpsFields == null) {
                resolveGpsCtor();
            }
            Object info;
            if (gpsCtor != null) {
                info = gpsCtor.newInstance(
                        loc.getAltitude(),                        // double altitude
                        loc.getLatitude(),                        // double latitude
                        loc.getLongitude(),                       // double longitude
                        1,                                        // int antennaState：1 = 天线可用
                        1,                                        // int pDop：无数据，给中性值
                        Math.round(loc.getSpeed() * 3.6f),        // int speed：km/h
                        Math.round(loc.getBearing()),             // int heading：0..359
                        0,                                        // int satsUsed
                        0,                                        // int satsVisible
                        loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis());
            } else if (gpsFields != null) {
                info = UCarCommon.GPSInfo.class.newInstance();
                setField(info, "altitude", loc.getAltitude());
                setField(info, "latitude", loc.getLatitude());
                setField(info, "longitude", loc.getLongitude());
                setField(info, "antennaState", 1);
                setField(info, "pDop", 1);
                setField(info, "speed", Math.round(loc.getSpeed() * 3.6f));
                setField(info, "heading", Math.round(loc.getBearing()));
                setField(info, "satsUsed", 0);
                setField(info, "satsVisible", 0);
                setField(info, "timeStamp", loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis());
            } else {
                return false;
            }
            return adapter.sendGPSInfo((UCarCommon.GPSInfo) info);
        } catch (Throwable t) {
            gpsReflectFailed = true;
            return false;
        }
    }

    /** 解析 GPSInfo 的构造方式：优先十参构造，退化到直接写字段。解析失败就永久停用。 */
    private static void resolveGpsCtor() {
        try {
            Constructor<?> c = UCarCommon.GPSInfo.class.getDeclaredConstructor(
                    double.class, double.class, double.class,
                    int.class, int.class, int.class, int.class, int.class, int.class,
                    long.class);
            c.setAccessible(true);
            gpsCtor = c;
            return;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field[] fs = new java.lang.reflect.Field[]{
                    UCarCommon.GPSInfo.class.getDeclaredField("altitude"),
                    UCarCommon.GPSInfo.class.getDeclaredField("latitude"),
                    UCarCommon.GPSInfo.class.getDeclaredField("longitude"),
                    UCarCommon.GPSInfo.class.getDeclaredField("antennaState"),
                    UCarCommon.GPSInfo.class.getDeclaredField("pDop"),
                    UCarCommon.GPSInfo.class.getDeclaredField("speed"),
                    UCarCommon.GPSInfo.class.getDeclaredField("heading"),
                    UCarCommon.GPSInfo.class.getDeclaredField("satsUsed"),
                    UCarCommon.GPSInfo.class.getDeclaredField("satsVisible"),
                    UCarCommon.GPSInfo.class.getDeclaredField("timeStamp"),
            };
            for (java.lang.reflect.Field f : fs) {
                f.setAccessible(true);
            }
            gpsFields = fs;
        } catch (Throwable t) {
            gpsReflectFailed = true;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        for (java.lang.reflect.Field f : gpsFields) {
            if (f.getName().equals(name)) {
                f.set(target, value);
                return;
            }
        }
    }

    // ==================================================================
    // 手机侧上报的格式化（只用于日志 / 未来的车机 UI）
    // ==================================================================

    static String describe(UCarCommon.MusicInfo info) {
        if (info == null) {
            return "music=null";
        }
        try {
            return "music " + (info.isPlaying() ? "▶" : "⏸")
                    + " " + safe(info.getTitle())
                    + " / " + safe(info.getArtistName())
                    + " [" + safe(info.getAlbumName()) + "]"
                    + " " + info.getPlayingCurrentTimeMs() + "/" + info.getTotalTimesMs() + "ms";
        } catch (Throwable t) {
            return "music=?";
        }
    }

    static String describe(UCarCommon.PhoneStateInfo info) {
        if (info == null) {
            return "phone=null";
        }
        try {
            return "phone state=" + info.getModemCallState()
                    + " voip=" + info.isVoipCall()
                    + " screenLocked=" + info.isScreenLocked();
        } catch (Throwable t) {
            return "phone=?";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
