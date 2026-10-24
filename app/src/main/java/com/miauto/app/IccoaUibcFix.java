package com.miauto.app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * UIBC（触控回传）的状态诊断与懒补。
 *
 * <p><b>为什么需要这个类</b>：反编译 {@code UibcManager.sendTouchEvent}（见
 * {@code docs/ICCOA-RUNTIME-FINDINGS.md} 第 8 节）发现三道前置检查：
 * <ol>
 *   <li>三个数组长度必须等于 count；</li>
 *   <li>{@code mIsEncryptionSettled} —— 只在 {@code onWfdSinkReady} 回调里置 true；</li>
 *   <li>{@code mSourceWidth/Height > 0} —— 只在 {@code updateVideoSourceSize} 里赋值，
 *       而后者唯一被触发的路径是 {@code onWfdSinkReady ← onCastVideoInitialized}。</li>
 * </ol>
 *
 * <p>问题在于 {@code CastListener} 的三个回调（onWfdAudioInitialized / onWfdSinkReady /
 * onWfdSinkStopped）全是 <b>WFD 无线专属</b>：USB（AOA）传输下媒体走另一条路，
 * {@code onCastVideoInitialized} 不触发 → {@code mSourceWidth/Height} 停在 0 →
 * 车机上点的每一刀都被 SDK 拒绝（日志里的「被拒」），而方控 {@code sendKeyEvent}
 * 不查这两项，所以按键照样好使。
 *
 * <p>坐标链也在这里：SDK 收到的 x/y 会按 {@code x * mDisplayWidth / mSourceWidth}
 * 重算，而 display 是 {@code startCast} 时由车机传入的 videoW/videoH，我们送进去的
 * 坐标正是按这个基准归一化的。只要 source 与 display 相等，映射就是 1:1。
 * 所以懒补的值取 display 尺寸（videoW × videoH），既不破坏无线的正确性，
 * 又顺带修掉「手机上报的源尺寸与视频画布不一致导致的位置错位」。
 *
 * <p>所有反射都带缓存与 try-catch：SDK 换版本导致方法/字段消失时，最坏退化为
 * 「没有诊断也没有懒补」，投屏本身不受影响。
 */
final class IccoaUibcFix {

    private IccoaUibcFix() {}

    /** UCarAdapter.updateVideoSourceSize(int, int) —— private，反射可达。 */
    private static volatile Method updateVideoSourceSize;
    /** UCarAdapter.mUibcManager —— private field。 */
    private static volatile Field uibcManagerField;
    /** UibcManager 的内部字段。 */
    private static volatile Field fSourceWidth;
    private static volatile Field fSourceHeight;
    private static volatile Field fDisplayWidth;
    private static volatile Field fDisplayHeight;
    private static volatile Field fEncryptionSettled;
    private static volatile Field fSessionId;

    private static volatile boolean resolved = false;
    private static volatile boolean warned = false;

    private static synchronized void resolve(Class<?> adapterClass) {
        if (resolved) return;
        resolved = true;
        try {
            updateVideoSourceSize = adapterClass.getDeclaredMethod(
                    "updateVideoSourceSize", int.class, int.class);
            updateVideoSourceSize.setAccessible(true);
        } catch (Throwable t) {
            warn("updateVideoSourceSize 不可用: " + t);
        }
        try {
            uibcManagerField = adapterClass.getDeclaredField("mUibcManager");
            uibcManagerField.setAccessible(true);
        } catch (Throwable t) {
            warn("mUibcManager 不可用: " + t);
        }
        try {
            Class<?> uibcClass = Class.forName("com.ucar.vehiclesdk.control.UibcManager");
            fSourceWidth = uibcClass.getDeclaredField("mSourceWidth");
            fSourceHeight = uibcClass.getDeclaredField("mSourceHeight");
            fDisplayWidth = uibcClass.getDeclaredField("mDisplayWidth");
            fDisplayHeight = uibcClass.getDeclaredField("mDisplayHeight");
            fEncryptionSettled = uibcClass.getDeclaredField("mIsEncryptionSettled");
            fSessionId = uibcClass.getDeclaredField("mSessionID");
            for (Field f : new Field[]{fSourceWidth, fSourceHeight, fDisplayWidth,
                    fDisplayHeight, fEncryptionSettled, fSessionId}) {
                f.setAccessible(true);
            }
        } catch (Throwable t) {
            warn("UibcManager 字段不可用: " + t);
        }
    }

    private static void warn(String msg) {
        if (!warned) {
            warned = true;
            android.util.Log.w("ICCOA", "[UIBC] " + msg);
        }
    }

    /**
     * 把 SDK 的 source size 反射补成 display 尺寸。
     *
     * @param adapter UCarAdapter 实例（可能为 null）
     * @param w       display 宽（videoW）
     * @param h       display 高（videoH）
     * @return true 表示反射调用成功执行
     */
    static boolean ensureSourceSize(Object adapter, int w, int h) {
        if (adapter == null || w <= 0 || h <= 0) return false;
        try {
            resolve(adapter.getClass());
            if (updateVideoSourceSize == null) return false;
            updateVideoSourceSize.invoke(adapter, w, h);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ICCOA", "[UIBC] 懒补失败: " + t);
            return false;
        }
    }

    /**
     * 读 SDK 内部 UIBC 状态，生成一行可读快照。
     *
     * <p>拿不到任何字段时返回 "unavailable" 而不是抛异常 —— 诊断信息永远不该
     * 把投屏链路本身搞挂。
     */
    static String snapshot(Object adapter) {
        if (adapter == null) return "no-adapter";
        try {
            resolve(adapter.getClass());
            if (uibcManagerField == null) return "no-uibc-field";
            Object uibc = uibcManagerField.get(adapter);
            if (uibc == null) return "no-uibc-instance";
            int sw = fSourceWidth == null ? -1 : fSourceWidth.getInt(uibc);
            int sh = fSourceHeight == null ? -1 : fSourceHeight.getInt(uibc);
            int dw = fDisplayWidth == null ? -1 : fDisplayWidth.getInt(uibc);
            int dh = fDisplayHeight == null ? -1 : fDisplayHeight.getInt(uibc);
            boolean enc = fEncryptionSettled != null && fEncryptionSettled.getBoolean(uibc);
            String sid = fSessionId == null ? null : (String) fSessionId.get(uibc);
            return "source=" + sw + "x" + sh
                    + " display=" + dw + "x" + dh
                    + " enc=" + (enc ? "settled" : "NO")
                    + " session=" + (sid == null || sid.isEmpty() ? "NONE" : sid);
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }
}
