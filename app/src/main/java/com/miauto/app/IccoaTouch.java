package com.miauto.app;

import android.view.MotionEvent;

/**
 * 触控回传（UIBC）的坐标映射与节流。
 *
 * <p>从 {@link IccoaManager#sendTouch} 里抽出来的原因：这段逻辑有两套坐标系假设，
 * 混在 1600 行的管理器里既难读也难改。
 *
 * <p>两种映射方式（由设置项 {@code touch-fit-mode} 选择）：
 * <ul>
 *   <li><b>拉伸</b>（默认）：SDK 把视频画布铺满整个 SurfaceView，按 View 尺寸线性映射即可。
 *       本项目 99% 的情况是这种 —— 面板 2298×1080、视频 1280×592，比例 2.128 与 2.162
 *       只差 1.6%，肉眼看不出来。</li>
 *   <li><b>等比</b>：画面按比例缩放并居中、四周留黑边。此时必须先扣掉黑边再映射，
 *       否则越靠边偏移越大（在 16:9 面板上播 2.16:1 视频时，右侧能差出上百像素）。</li>
 * </ul>
 */
final class IccoaTouch {

    /** 同时按下的手指上限。车机面板一般 5 点，留 16 足够。 */
    private static final int MAX_POINTERS = 16;

    private final int[] ids = new int[MAX_POINTERS];
    private final int[] xs = new int[MAX_POINTERS];
    private final int[] ys = new int[MAX_POINTERS];

    private long lastMoveMs = 0L;
    private int logBudget = 12;
    private int count = 0;

    /**
     * MOVE 节流到约 60Hz。
     *
     * <p>车机触摸面板采样率通常 100~120Hz，逐事件转发会把 UIBC 通道塞满
     * （每个 MOVE 都要组 JSON + 加密 + 走 RTP），在 2.4GHz 链路上反而把延迟推高。
     * DOWN / UP / CANCEL 一律不节流，保证点击手感。
     */
    boolean shouldDrop(int action, long now) {
        if (action != MotionEvent.ACTION_MOVE) {
            return false;
        }
        // 0.3.16：16ms（≈60Hz）→ 20ms（50Hz）。拖动时 UIBC 每帧要组 JSON + 加密 + 走 RTP，
        // 骁龙 665 上 60Hz 的触摸流会和视频解码抢 CPU，表现为「一边拖一边卡」。
        // 50Hz 对手感几乎无感，但事件量少 20%。
        if (now - lastMoveMs < 20L) {
            return true;
        }
        lastMoveMs = now;
        return false;
    }

    void resetLogBudget() {
        logBudget = 12;
    }

    boolean takeLogSlot() {
        if (logBudget <= 0) {
            return false;
        }
        logBudget--;
        return true;
    }

    /**
     * 把一次 MotionEvent 映射到视频画布坐标。
     *
     * @return 有效触点数；0 表示参数非法（View 或视频尺寸还没就绪）
     */
    int map(MotionEvent e, int viewW, int viewH, int videoW, int videoH, boolean fitMode) {
        count = 0;
        if (e == null || viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
            return 0;
        }

        float scaleX = (float) videoW / viewW;
        float scaleY = (float) videoH / viewH;
        float offsetX = 0f;
        float offsetY = 0f;

        if (fitMode) {
            // 等比：先算画面在 View 里的实际显示区域，再把触点减去黑边、除以缩放比。
            final float fit = Math.min((float) viewW / videoW, (float) viewH / videoH);
            final float shownW = videoW * fit;
            final float shownH = videoH * fit;
            offsetX = (viewW - shownW) / 2f;
            offsetY = (viewH - shownH) / 2f;
            scaleX = 1f / fit;
            scaleY = 1f / fit;
        }

        final int n = Math.min(e.getPointerCount(), MAX_POINTERS);
        for (int i = 0; i < n; i++) {
            ids[i] = e.getPointerId(i);
            int x = Math.round((e.getX(i) - offsetX) * scaleX);
            int y = Math.round((e.getY(i) - offsetY) * scaleY);
            // 钳制到画面内。车机触摸屏的边缘值可能略超出 View 范围（面板黑边、系统手势区），
            // 越界坐标会被对端当成非法事件丢弃。
            xs[i] = Math.max(0, Math.min(videoW - 1, x));
            ys[i] = Math.max(0, Math.min(videoH - 1, y));
        }
        count = n;
        return count;
    }

    int[] ids() {
        return ids;
    }

    int[] xs() {
        return xs;
    }

    int[] ys() {
        return ys;
    }

    int count() {
        return count;
    }

    /**
     * 按 count 截取的数组（0.3.6 修复触屏全拒的关键）。
     *
     * <p>SDK 的 sendTouchEvent 会检查 {@code ids.length == count && xs.length == count
     * && ys.length == count}，不满足直接打 "count error!" 并拒绝。内部复用缓冲是
     * 固定 16 长度，直接把缓冲传过去等于宣告每一刀都非法 —— 这就是「触摸永不生效」
     * 的最终根因。必须在调用点截取到实际 count。
     */
    int[] idsTrimmed() {
        return java.util.Arrays.copyOf(ids, count);
    }

    int[] xsTrimmed() {
        return java.util.Arrays.copyOf(xs, count);
    }

    int[] ysTrimmed() {
        return java.util.Arrays.copyOf(ys, count);
    }
}
