package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import com.miauto.app.CpuTuner
import java.util.ArrayDeque

/** Small, shareable report. Never exports saved credentials or raw protocol payloads. */
object DiagnosticReport {
    fun build(context: Context): String {
        val settings = Settings(context)
        val ssid = settings.hotspotSsid
        val password = settings.hotspotPassword
        val lines = ArrayDeque<String>()
        fun accept(line: String) {
            DiagnosticReportRedactor.redact(line, ssid, password)?.let {
                if (lines.size == 6000) lines.removeFirst()
                lines.addLast(it)
            }
        }
        val history = DiagnosticJournal.snapshot()
        val source = if (settings.logSource == Settings.LogSource.APPLOG_FILE)
            AppLog.currentLogFile ?: AppLog.lastLogFile else null
        if (history.isNotEmpty()) {
            history.forEach(::accept)
        } else if (source?.isFile == true) {
            source.useLines { it.forEach(::accept) }
        }

        // ★ 无论上面走哪条分支，都再补一段 SDK 自己的日志。
        //
        //   原因：DiagnosticJournal 只收集 AppLog（tag=OPENHU）写入的行，而 ICCOA SDK 走
        //   com.easy.logger.EasyLog（tag = "easylog_" + 类名），这条路径根本不经过 AppLog。
        //   而 journal 在绝大多数会话里都非空 —— 于是下面那段 logcat 代码从来没被执行过，
        //   无论 tag 参数怎么改都拿不到 SDK 的输出。这里把它独立出来，只取 easylog_ 行，
        //   避免与 journal 里的 OPENHU 记录重复。
        runCatching { readSdkLogcat().forEach(::accept) }
            .onFailure { accept("[diagnostic] SDK logcat unavailable: ${it.message}") }

        // ★ native 层 UIBC/WFD 日志（0.3.5 新增）。
        //
        //   UibcManager.sendTouchEvent 的最后一跳是 SinkNative.onUibcEvent（native 方法，
        //   实现在 libovmsink.so）。它返回 false 时 Java 层看不到原因，但 native 栈会打
        //   "uibc->started, connect failed!" / "uibc->event, ECONNRESET, EPIPE!" /
        //   "send datalen check failed" / "wfd_uibc_capability ..." 这类日志 —— 全部不进
        //   easylog_ 通道，0.3.4 的报告抓不到。这里按关键词补抓，这是触屏失灵的最后拼图。
        runCatching { readNativeUibcLogcat().forEach(::accept) }
            .onFailure { accept("[diagnostic] native uibc logcat unavailable: ${it.message}") }

        // ★ 系统崩溃缓冲（0.3.4 新增）。Java 层的 UncaughtExceptionHandler 抓不到
        //   native crash（SinkNative.so / 解码器段错误、tombstone），而这类崩溃
        //   恰恰是「App 看起来自己没了、报告里却什么都没有」的元凶。
        //   logcat -b crash 是系统级环形缓冲，进程死了记录也在，runCatching 兜底
        //   权限/参数不支持的情况。
        runCatching { readCrashBuffer().forEach(::accept) }
            .onFailure { accept("[diagnostic] crash logcat unavailable: ${it.message}") }

        // ★ 音频与射频（0.3.19 E3-2）。
        //
        //   「声音断续」这件事在原报告里是完全的盲区：诊断只抓 OPENHU 与 easylog_，
        //   而音频输出走 AudioFlinger/AudioTrack，射频走 WifiP2pService/wpa_supplicant，
        //   两边一条都不进来。没有这两组日志，就只能猜「是不是网络」。
        //   只在诊断时段抓，不影响平时性能。
        runCatching { readAudioRadioLogcat().forEach(::accept) }
            .onFailure { accept("[diagnostic] audio/radio logcat unavailable: ${it.message}") }

        // ★ AudioFlinger 欠载计数（0.3.21）。
        //
        //   0.3.20 的报告里音频段只剩 118 行 / 4 秒 —— logcat 尾巴被
        //   UCar_AudioQueueManager-pcm 的 "size: 10" 常规行占满了，一次 underrun 现场
        //   都没落进窗口。上面的过滤已经压掉常规行，这里再补一份"计数器"：
        //   dumpsys 的 underruns 字段是累计值，不受采样窗口影响，能直接回答
        //   「到底被抽干过几次」。需要 root，没有就写一行原因。
        runCatching { readAudioFlingerUnderruns().forEach(::accept) }
            .onFailure { accept("[diagnostic] audio_flinger dump unavailable: ${it.message}") }

        // ★ 崩溃文件正文（0.3.19 B2）。
        //   10/28 的 0.3.18 崩了两次，但报告里只有 App 启动时那句「[崩溃记录] xxx.txt」，
        //   栈根本没进报告 —— 等于知道崩了却不知道崩在哪。这里把最新一份的前 40 行带上。
        runCatching { readLatestCrashFile(context).forEach(::accept) }
            .onFailure { accept("[diagnostic] crash file unavailable: ${it.message}") }
        return buildString {
            appendLine("DiAuto ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · diagnostic report")
            appendLine("Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}; board=${Build.BOARD}; build=${Build.DISPLAY}")
            appendLine("Wireless mode=${settings.wifiConnectionMode}; native transport=${settings.nativeApTransport}")
            appendLine("Hotspot auto-enable=${settings.autoEnableHotspot}; hotspot state=${SoftApStateReader.read(context)}")
            appendLine("Hotspot name saved=${settings.hotspotSsid.isNotBlank()}; password saved=${settings.hotspotPassword.isNotEmpty()}")
            appendLine("Logging level=${settings.exporterLogLevel}; source=${settings.logSource}")
            runCatching { appendLine("CPU: ${CpuTuner.snapshot()}") }
            runCatching { appendLine("SU: ${CpuTuner.probe()}") }
            appendLine("Saved credentials, network addresses and protocol payloads are omitted.")
            appendLine("--- Recent app logs (up to 6000 lines) ---")
            if (lines.isEmpty()) appendLine("No recent app logs available. Reproduce the issue and save a new report.")
            lines.forEach { appendLine(it) }
        }
    }

    /** 只取 ICCOA SDK 的行（tag 形如 easylog_UCarConnectProxy）。 */
    private fun readSdkLogcat(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().useLines { input ->
                input.filter { it.contains("easylog_") }.toList()
            }
        } finally {
            process.destroy()
        }
    }

    /**
     * native 层 UIBC/WFD 日志（libovmsink.so 的 __android_log 输出）。
     *
     * 关键词覆盖：uibc（会话建立/发送失败）、ovmsink（库名可能作 tag）、
     * wfd_uibc_capability（RTSP 协商）、NativeRemoteDisplay。上限 300 行。
     */
    private fun readNativeUibcLogcat(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().useLines { input ->
                input.filter { line ->
                    val lower = line.lowercase()
                    lower.contains("uibc") || lower.contains("ovmsink") ||
                        lower.contains("nativeremotedisplay")
                }.take(300).toList()
            }
        } finally {
            process.destroy()
        }
    }

    /**
     * 音频 / 射频侧日志（0.3.19 E3-2，0.3.21 改过滤策略）。
     *
     * <p>覆盖三类：
     * <ul>
     *   <li>AudioFlinger / AudioTrack / AudioPolicy —— 欠载（underrun）、缓冲、
     *       采样率切换，是「声音断续」最直接的证据</li>
     *   <li>WifiP2p / wpa_supplicant / WifiHAL —— 组建立、掉线、信道切换、重传</li>
     *   <li>lowmemorykiller / Choreographer —— 排除「被系统杀 / 主线程卡住」</li>
     * </ul>
     *
     * <p><b>0.3.21 的改动：</b>0.3.20 的报告里这一段只剩 118 行、覆盖 4 秒 ——
     * {@code UCar_AudioQueueManager-pcm: buffer queue size: 10} 每秒刷十余条，
     * 把 500 行的配额全吃掉了，真正有价值的 {@code wait buffer to fill} 一条没进来。
     * 现在把「队列满」的常规行按 30:1 抽样，省下的额度全部让给异常行，
     * 上限同时提到 760。
     */
    private fun readAudioRadioLogcat(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true).start()
        return try {
            val out = ArrayList<String>(800)
            var routineSeen = 0
            process.inputStream.bufferedReader().useLines { input ->
                input.forEach { line ->
                    val t = line.lowercase()
                    val hit = t.contains("audioflinger") || t.contains("audiotrack") ||
                        t.contains("audiopolicy") || t.contains("ucar_audio") ||
                        t.contains("wifip2p") || t.contains("wpa_supplicant") ||
                        t.contains("wifihal") || t.contains("lowmemorykiller") ||
                        t.contains("underrun") || t.contains("glitch")
                    if (!hit) return@forEach
                    if (isRoutineQueueLine(t)) {
                        routineSeen++
                        if (routineSeen % 30 != 0) return@forEach
                    }
                    if (out.size < 760) out.add(line)
                }
            }
            out
        } finally {
            process.destroy()
        }
    }

    /** 队列满的常规行：{@code buffer queue size: 9..N}。这类行只在掉水位时才有信息量。 */
    private val queueSizeRe = Regex("buffer queue size:\\s*(\\d+)")

    private fun isRoutineQueueLine(lower: String): Boolean {
        if (!lower.contains("ucar_audioqueuemanager")) return false
        val n = queueSizeRe.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return n >= 9
    }

    /**
     * AudioFlinger 的累计欠载计数（0.3.21）。
     *
     * <p>为什么一定要这一份：logcat 是环形缓冲，只留最近几秒；而「断一下」是秒级以下
     * 的事件，靠抽日志碰运气。{@code dumpsys media.audio_flinger} 里的 underruns
     * 是**从 track 创建起的累计值**，采样窗口再窄也不影响它 —— 有增长就说明硬件缓冲
     * 真的被抽干过，没有增长就说明声音一直有货、问题在别处。
     *
     * <p>需要 root。普通 App 调 dumpsys 会被 DUMP 权限挡掉，这里直接走 su。
     */
    private fun readAudioFlingerUnderruns(): List<String> {
        if (!CpuTuner.rootGranted()) {
            return listOf("[audio] dumpsys 跳过：su 未授权，无法读 AudioFlinger 计数")
        }
        val dump = CpuTuner.exec("dumpsys media.audio_flinger")
            ?: return listOf("[audio] dumpsys media.audio_flinger 执行失败")
        val picked = dump.lineSequence()
            .filter { it.contains("underrun", ignoreCase = true) }
            .take(40)
            .toList()
        if (picked.isEmpty()) {
            return listOf("[audio] dumpsys 无 underrun 字段（该版本可能不统计，或本次没有 track）")
        }
        return buildList {
            add("[audio] ==== AudioFlinger underrun（${picked.size} 行，累计值）====")
            addAll(picked)
        }
    }

    /**
     * 最新一份 crash-*.txt 的正文（0.3.19 B2）。
     *
     * <p>只取前 40 行：Java 栈的头 40 行已经能看出异常类型 + 前几层调用，
     * 再多的帧对定位帮助有限，反而挤占日志预算。
     */
    private fun readLatestCrashFile(context: Context): List<String> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?: return emptyList()
        val latest = files.maxByOrNull { it.lastModified() } ?: return emptyList()
        val head = latest.readLines().take(40)
        if (head.isEmpty()) return emptyList()
        return buildList {
            add("[crash] ==== ${latest.name}（共 ${latest.readLines().size} 行，此处前 ${head.size} 行）====")
            addAll(head)
            add("[crash] ==== 结束 ====")
        }
    }

    /**
     * 系统崩溃缓冲：Java handler 抓不到的 native crash / tombstone 在这里。
     *
     * 过滤条件放宽到「本进程相关」：包名、进程名、以及崩溃缓冲里必然出现的
     * FATAL/tombstone/signal 关键字。返回行数上限 200，防止缓冲里堆积的
     * 历史崩溃把报告主体冲掉。
     */
    private fun readCrashBuffer(): List<String> {
        val process = ProcessBuilder("logcat", "-b", "crash", "-d", "-v", "threadtime")
            .redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().useLines { input ->
                input.filter { line ->
                    line.contains("FATAL") || line.contains("tombstone") ||
                        line.contains("signal ") || line.contains("miauto", ignoreCase = true)
                }.take(200).toList()
            }
        } finally {
            process.destroy()
        }
    }
}
