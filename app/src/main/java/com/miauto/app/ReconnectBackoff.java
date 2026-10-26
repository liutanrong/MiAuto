package com.miauto.app;

/**
 * 「连不上之后隔多久再重播一次」的纯判定（0.3.17）。
 *
 * <p><b>为什么要退避</b>：一次异常断连后，如果车机固定 1 秒就重新广播，手机端的 Wi-Fi P2P 组
 * 往往还在拆除/重建，于是连续撞 {@code 10003}（无线建链失败）/ {@code 10006}（建链超时）/
 * {@code 10008}（连接内部错误）。每撞一次都把共用射频往前推一步，恢复时间反而被拉长。
 * 社区实测（Gitee <code>guo-zhc/MiAuto</code> 0.1.9）：固定 1.2 秒时从断开到真正连回花了近 80 秒，
 * 改成指数退避后明显收敛。
 *
 * <p>只对「无线建链」这一族错误码退避；参数类错误（如投屏启动失败）与射频回收无关，
 * 维持固定 base 间隔，避免把无关失败也拖慢。
 *
 * <p>纯逻辑、不碰 Android/SDK，便于把「哪几档延迟、什么时候封顶、什么时候复位」钉死。
 */
final class ReconnectBackoff {

    /** 首次（或非无线建链类失败）重播间隔。 */
    static final long BASE_MS = 1_200L;

    /** 退避上限：再久也不超过它，保证最终一定能连回。 */
    static final long MAX_MS = 8_000L;

    /** 无线建链这一族错误码：P2P/热点建不起来，或握手无疾而终。 */
    static boolean isWirelessBringupFailure(int errorCode) {
        return errorCode == 10003 || errorCode == 10006 || errorCode == 10008;
    }

    /**
     * 下一次重播该隔多久。
     *
     * @param wirelessFailure 本次是否为无线建链类失败
     * @param failureStreak   本会话内连续无线建链失败次数（含本次），从 1 起
     */
    static long nextDelayMs(boolean wirelessFailure, int failureStreak) {
        if (!wirelessFailure) return BASE_MS;
        long delay = BASE_MS;
        for (int i = 1; i < Math.max(1, failureStreak) && delay < MAX_MS; i++) {
            delay = Math.min(delay * 2, MAX_MS);
        }
        return delay;
    }

    /** 连续失败计数如何推进；非无线建链类失败不累加（归零）。 */
    static int streakAfterFailure(int streakBefore, int errorCode) {
        if (!isWirelessBringupFailure(errorCode)) return 0;
        return Math.max(0, streakBefore) + 1;
    }

    static int resetStreak() {
        return 0;
    }

    /** 错误码的可读名（SDK 的 UCarConnectState.ErrorCode）。 */
    static String errorName(int code) {
        switch (code) {
            case 10001: return "初始化失败 ERROR_INIT_FAILED";
            case 10002: return "参数非法 ERROR_INVALID_PARAMETER";
            case 10003: return "无线建链失败 ERROR_WIRELESS_CONNECT_FAILED";
            case 10004: return "USB 建链失败 ERROR_USB_CONNECT_FAILED";
            case 10005: return "投屏启动失败 ERROR_START_CAST_FAILED";
            case 10006: return "连接超时 ERROR_CONNECT_TIMEOUT";
            case 10007: return "内部错误 ERROR_CONNECT_INNER_FAILED";
            case 10008: return "用户介入超时 ERROR_USER_INTERVENTION_TIMEOUT";
            default: return "未知错误码";
        }
    }
}
