package com.miauto.app;

/**
 * 单场投屏会话的计时与计数。
 *
 * <p>原先这些数字以零散的 {@code long} 字段堆在 {@link IccoaManager} 里，汇总时又用
 * 字符串拼接重复算一遍（"协商90→100=?" 这种半截输出就是算漏了）。抽出来之后：
 * 计时只在事件发生时写一次，汇总只读不写，也就不会出现"报告里的数字和日志对不上"。
 *
 * <p>线程模型：所有写入都在主线程（SDK 回调经 {@code main.post} 转过来），
 * 不做同步。
 */
final class IccoaSessionStats {

    /** 本进程内第几次 startCast（含重连）。用于区分"首场"和"重连"。 */
    int castCount = 0;
    /** 本进程内发生过几次自动重连（冷启动快速判死 + 中途失效都算）。 */
    int reconnectCount = 0;

    long castStartedAt = 0L;
    long progress90At = 0L;
    long progress100At = 0L;
    long firstFrameAt = 0L;

    /** 首帧相对 progress100 的耗时；若首帧早于 progress100，则为相对 startCast。 */
    long lastFirstFrameMs = -1L;
    boolean firstFrameBeforeProgress100 = false;

    /** 本会话里「≥2 指」的触摸事件数 / 最大手指数（0.3.15，用于验证双指缩放是否真的发出去了）。 */
    int multiTouchEvents = 0;
    int multiTouchMax = 0;

    int connectType = -1;
    String phoneModel = "";
    int surfaceW = -1;
    int surfaceH = -1;
    int fps = 0;

    void onCastStart() {
        castCount++;
        castStartedAt = System.currentTimeMillis();
        progress90At = 0L;
        progress100At = 0L;
        firstFrameAt = 0L;
        lastFirstFrameMs = -1L;
    }

    void onProgress(int progress) {
        long now = System.currentTimeMillis();
        if (progress == 90 && progress90At == 0L) {
            progress90At = now;
        } else if (progress >= 100 && progress100At == 0L) {
            progress100At = now;
        }
    }

    void onFirstFrame() {
        long now = System.currentTimeMillis();
        firstFrameAt = now;
        firstFrameBeforeProgress100 = (progress100At == 0L);
        if (firstFrameBeforeProgress100) {
            lastFirstFrameMs = castStartedAt == 0L ? -1L : now - castStartedAt;
        } else {
            lastFirstFrameMs = now - progress100At;
        }
    }

    void resetForNewSession() {
        castCount = 0;
        reconnectCount = 0;
        castStartedAt = 0L;
        progress90At = 0L;
        progress100At = 0L;
        firstFrameAt = 0L;
        lastFirstFrameMs = -1L;
    }

    /** 汇总一行，写进诊断报告。缺数据的项写 {@code ?}，不猜。 */
    String summary() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("res=");
        if (surfaceW > 0 && surfaceH > 0) {
            sb.append(surfaceW).append('x').append(surfaceH);
        } else {
            sb.append('?');
        }
        if (fps > 0) {
            sb.append(" fps=").append(fps);
        }

        if (progress90At > 0 && progress100At > 0) {
            sb.append(" 协商90→100=").append(progress100At - progress90At).append("ms");
        } else {
            sb.append(" 协商90→100=?");
        }

        if (lastFirstFrameMs >= 0) {
            sb.append(" 首帧=").append(lastFirstFrameMs).append("ms")
                    .append(firstFrameBeforeProgress100 ? "(自startCast)" : "(自progress100)");
        } else {
            sb.append(" 首帧=未就绪");
        }

        if (multiTouchEvents > 0) {
            sb.append(" 多点=").append(multiTouchEvents).append("次/最多").append(multiTouchMax).append("指");
        }
        sb.append(" 会话=").append(castCount).append(" 重连=").append(reconnectCount);
        if (connectType > 0) {
            // 0.3.15：旧写法把 2（USB AOA）也归进 else 打成 "SoftAP"，与角标自相矛盾。
            sb.append(" 通道=").append(connectType == 1 ? "P2P"
                    : connectType == 2 ? "USB"
                    : connectType == 3 ? "SoftAP"
                    : ("?" + connectType));
        }
        if (!phoneModel.isEmpty()) {
            sb.append(" 手机=").append(phoneModel);
        }
        sb.append(" 结果=").append(lastFirstFrameMs >= 0 ? "正常" : "黑屏");
        return sb.toString();
    }
}
