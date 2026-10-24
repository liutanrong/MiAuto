package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
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
        return buildString {
            appendLine("DiAuto ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · diagnostic report")
            appendLine("Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}; board=${Build.BOARD}; build=${Build.DISPLAY}")
            appendLine("Wireless mode=${settings.wifiConnectionMode}; native transport=${settings.nativeApTransport}")
            appendLine("Hotspot auto-enable=${settings.autoEnableHotspot}; hotspot state=${SoftApStateReader.read(context)}")
            appendLine("Hotspot name saved=${settings.hotspotSsid.isNotBlank()}; password saved=${settings.hotspotPassword.isNotEmpty()}")
            appendLine("Logging level=${settings.exporterLogLevel}; source=${settings.logSource}")
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
