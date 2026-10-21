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
}
