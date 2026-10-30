package com.andrerinas.openheadunit.utils

import java.util.ArrayDeque

/**
 * 会话期间的音频链路追踪（0.3.22）。
 *
 * <p>为什么需要它：0.3.19→0.3.21 连续三份报告里，音频段始终只有 **4 秒**。
 * `logcat -d` 读的是系统环形缓冲，而 ICCOA SDK 的 `UCar_AudioQueueManager-pcm`
 * 每秒要打十几条 `buffer queue size: 10`，几秒就把本进程的缓冲灌满 —— 无论导出侧
 * 怎么过滤，能读到的历史都只有那 4 秒。0.3.21 把配额从 500 提到 760，方向就是错的：
 * 配额不是瓶颈，缓冲长度才是。
 *
 * <p>解法只有一个：**在会话开始时就把 logcat 挂上**，边产生边收，自己维护一份
 * 环形缓冲。这样导出时能拿到整场（10 分钟以上），而不是尾巴上的 4 秒。
 *
 * <p>额外做了一层去重：水位行只在**数值变化时**保留。稳定在满水位时一行不记，
 * 掉到 9 / 7 / 0 时每次变化都记 —— 既省空间，又恰好是最需要看的那几行。
 */
object AudioTrace {

    /** 环形上限。变化行很稀疏，3000 行足够覆盖半小时以上。 */
    private const val MAX_LINES = 1500

    /** logcat 里值得留的 tag。全部由本进程产生（SDK 跑在我们的进程里）。 */
    private val TAGS = arrayOf(
        "UCar_AudioPlayer",
        "UCar_AudioQueueManager",
        "UCar_AudioManager",
        "UCar_AudioCodec",
        "AudioTrack",
        "AudioFlinger",
        "AudioPolicyManager"
    )

    /** 这些关键词一旦出现就必须保留，不做任何抽样。 */
    private val ALWAYS = arrayOf(
        "wait buffer to fill",
        "buffer fill done",
        "Get data from buffer queue error",
        "InterruptedException",
        "underrun",
        "AudioTrack: stop",
        "notifyTrackSessionId",
        "dead object",
        "deadObject"
    )

    /** `buffer queue size: N, total: M` 的解析。 */
    private val QUEUE_RE = Regex(
        """UCar_AudioQueueManager-[A-Za-z0-9_]*@([^:]+):\s*buffer queue size:\s*(\d+),\s*total:\s*(\d+)"""
    )

    private val lines = ArrayDeque<String>()

    /** 每个 stream 上一次见到的水位，用于去重。 */
    private val lastLevel = HashMap<String, Int>()

    /** 每个 stream 见过的最高水位 —— 也就是它的"满值"，因 TTS 是 5、MUSIC 是 10。 */
    private val peakLevel = HashMap<String, Int>()

    /** 每个 stream 低于「满值一半」的次数 —— 这才是真正的水位告警。 */
    private val lowCount = HashMap<String, Int>()

    /** 每个 stream 见底（0）的次数。 */
    private val zeroCount = HashMap<String, Int>()

    /** 每个 stream 的最低水位。 */
    private val minLevel = HashMap<String, Int>()

    @Volatile private var process: Process? = null
    @Volatile private var running = false

    /**
     * 会话开始时挂上。重复调用无副作用。
     *
     * <p>不清理已有内容：上一场的水位记录和这场一样有价值，报告里能连成时间轴。
     * 满了由环形逻辑自动淘汰最旧的。
     */
    @JvmStatic
    @Synchronized
    fun start() {
        if (running) return
        val p = try {
            // 不带 -d：挂住持续输出。不指定 -T，从当前时刻起算，避免把历史重复灌一遍。
            ProcessBuilder("logcat", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            record("[音频] 追踪启动失败：${t.message}")
            return
        }
        process = p
        running = true
        Thread({
            try {
                p.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        if (!running) break
                        offer(line)
                    }
                }
            } catch (_: Throwable) {
                // 进程被 destroy 时 readLine 会抛，正常路径，不记。
            } finally {
                // logcat 自己退了（权限不足 / 参数不支持）。不把 running 复位的话，
                // 下一场会话 start() 会直接返回，整场静默 —— 那比没有更难查。
                if (running) {
                    running = false
                    process = null
                    record("[音频] 追踪提前结束（logcat 自行退出，可能是 READ_LOGS 权限不足）")
                }
            }
        }, "DiAuto-audio-trace").apply {
            isDaemon = true
            start()
        }
        record("[音频] 追踪已启动（会话期间常驻，面板水位只在变化时记录）")
    }

    /** 会话结束时摘下。内容保留，等报告导出时取走。 */
    @JvmStatic
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { process?.destroy() }
        process = null
    }

    /**
     * 本场水位摘要，会话结束时写进应用日志。
     *
     * <p>这一行就是「这一场断了几次、断到多低」的量化答案，不必再靠翻 logcat。
     */
    @JvmStatic
    fun summary(): String = synchronized(lines) {
        if (peakLevel.isEmpty()) return "无样本（追踪未启动，或本场 SDK 未起播音频）"
        val sb = StringBuilder("本场水位：")
        var first = true
        for (stream in peakLevel.keys.sorted()) {
            if (!first) sb.append("；")
            first = false
            sb.append(stream)
                .append(" 满 ").append(peakLevel[stream])
                .append(" / 最低 ").append(minLevel[stream] ?: -1)
                .append(" / 见底 ").append(zeroCount[stream] ?: 0).append(" 次")
                .append(" / 低于半满 ").append(lowCount[stream] ?: 0).append(" 次")
        }
        sb.toString()
    }

    @JvmStatic
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    @JvmStatic
    fun hasContent(): Boolean = synchronized(lines) { lines.isNotEmpty() }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun record(line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }

    private fun offer(raw: String) {
        if (raw.isEmpty()) return
        val always = ALWAYS.any { raw.contains(it) }
        if (!always) {
            if (TAGS.none { raw.contains(it) }) return
            // 水位行：只在变化时留。稳定满水位时一行不记。
            val m = QUEUE_RE.find(raw)
            if (m != null) {
                val stream = m.groupValues[1]
                val level = m.groupValues[2].toIntOrNull() ?: return
                val total = m.groupValues[3].toIntOrNull() ?: 0
                synchronized(lines) {
                    val prev = lastLevel[stream]
                    val peak = peakLevel[stream] ?: 0
                    if (level > peak) peakLevel[stream] = level
                    val full = peakLevel[stream] ?: level
                    minLevel[stream] = minOf(minLevel[stream] ?: level, level)
                    if (level == 0) zeroCount[stream] = (zeroCount[stream] ?: 0) + 1
                    if (level * 2 < full) lowCount[stream] = (lowCount[stream] ?: 0) + 1
                    if (prev != null && prev == level) return
                    lastLevel[stream] = level
                }
                // 水位没变：不落盘，但 total 增长说明还在进数据，不必记。
                record(raw)
                return
            }
            // 其它 tag 的常规行：只留 AudioTrack 的状态变化和错误级别，噪声太大会挤掉现场。
            if (raw.contains(" AudioTrack: ") && !raw.contains(" stop") && !raw.contains("start")) {
                // AudioTrack 的 write/obtainBuffer 每帧都有，直接丢。
                return
            }
        }
        record(raw)
    }
}
