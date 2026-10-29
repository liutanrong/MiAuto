package com.miauto.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

/**
 * CPU 调频采样与实时档位切换（0.3.21）。
 *
 * <p><b>为什么需要它：</b>10/29 的实测反馈是「断得随机、每次很短、之后一下全播出来」。
 * 这三个特征合起来指向缓冲欠载后追赶 —— 数据一帧没丢，只是晚了。丢包补不回来，
 * 追赶能补回来，所以瓶颈不在带宽，在时序。
 *
 * <p>USB 不卡、Wi-Fi 卡，差的正是「每一包都要打断一次 CPU」。骁龙 665 的车机版
 * 通常跑 schedutil 或 powersave：平时低频，负载上来要先爬频率，爬升那几十毫秒
 * 正好够把 AudioTrack 抽干。这条假设只要看「断音时刻的频率是不是低的」就能证实
 * 或证伪，所以这里先提供两个能力：
 *
 * <ul>
 *   <li>{@link #snapshot()} —— 一行读出 8 核实时频率与 governor，采样开销近乎为零</li>
 *   <li>{@link #applyPerformance} / {@link #restore} —— 会话期间切 performance，结束还原</li>
 * </ul>
 *
 * <p><b>关于兜底：</b>如果进程在会话中被杀，governor 会停在被改过的状态。原值记在
 * SharedPreferences 里，App 每次启动调一次 {@link #restore} —— 宁可多一次无害的写入，
 * 也不要让车机长期锁在满频。
 *
 * <p>直接用 libsu 而不是 {@code SUExecutor}：后者要等 {@code checkPermission()} 被调用
 * 才有 active 实现，而那条路只有 FYT 车机会走。这里用 {@code isAppGrantedRoot()} 只查状态、
 * 不触发授权弹窗 —— 没 root 就安静放弃，不能让一次诊断把用户卡在授权对话框上。
 */
public final class CpuTuner {

    private static final String TAG = "IccoaCpu";
    private static final int MAX_CORES = 8;
    private static final String CPU_SYS = "/sys/devices/system/cpu";

    private static final String PREFS = "icoca-cpu";
    /** 切换前 governor 的原值。存在即代表「有一次改动没还原」。 */
    private static final String KEY_SAVED = "governor-before-perf";

    private CpuTuner() {
    }

    // ── 读 ────────────────────────────────────────────────────────────────

    /** 当前 governor；读不到返回 null（部分车机把 cpufreq 节点收紧到 system 组）。 */
    public static String governor() {
        return readFirstLine(CPU_SYS + "/cpu0/cpufreq/scaling_governor");
    }

    /** 内核支持的 governor 列表；用于判断 performance 是否可选。 */
    public static String availableGovernors() {
        return readFirstLine(CPU_SYS + "/cpu0/cpufreq/scaling_available_governors");
    }

    /** 8 核实时频率（kHz），offline 的核返回 -1。 */
    public static int[] freqsKhz() {
        int[] out = new int[MAX_CORES];
        for (int i = 0; i < MAX_CORES; i++) {
            out[i] = parseIntOr(readFirstLine(CPU_SYS + "/cpu" + i + "/cpufreq/scaling_cur_freq"), -1);
        }
        return out;
    }

    /**
     * 一行摘要，直接进日志：{@code gov=schedutil | 1804 1804 800 800 1804 800 800 800 MHz}。
     *
     * <p>不省略任何核：断音是否由「某个小核掉到最低频」引起，只看汇总值看不出来。
     */
    public static String snapshot() {
        int[] f = freqsKhz();
        StringBuilder sb = new StringBuilder(96);
        String gov = governor();
        sb.append("gov=").append(gov == null ? "?" : gov).append(" |");
        boolean any = false;
        for (int v : f) {
            if (v < 0) {
                sb.append(" -");
            } else {
                any = true;
                sb.append(' ').append(v / 1000);
            }
        }
        sb.append(any ? " MHz" : " MHz（sysfs 不可读）");
        return sb.toString();
    }

    /** su 是否可用。只查状态，不弹授权框。 */
    public static boolean rootGranted() {
        try {
            return Boolean.TRUE.equals(Shell.isAppGrantedRoot());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 身份探针：报告里那句「到底有没有 root」就靠它。 */
    public static String probe() {
        if (!rootGranted()) return "su 未授权（isAppGrantedRoot=false）";
        String out = exec("id");
        if (out == null || out.trim().isEmpty()) return "su 已授权但 id 无输出";
        return out.trim();
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    /**
     * 会话开始：切 performance，原值记账。
     *
     * <p>已经存过原值就不再覆盖 —— 连续会话里第二次读到的「原值」会是 performance，
     * 那样还原时就把 performance 当成用户的设置永久写回去了。
     */
    public static String applyPerformance(Context ctx) {
        try {
            String cur = governor();
            if (cur == null) return "跳过：scaling_governor 读不到（sysfs 权限）";
            if ("performance".equals(cur)) return "已是 performance，无需切换";
            String avail = availableGovernors();
            if (avail != null && !avail.contains("performance")) {
                return "跳过：内核不支持 performance（可用 " + avail.trim() + "）";
            }
            if (!rootGranted()) return "跳过：su 未授权，无法写入";

            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!p.contains(KEY_SAVED)) {
                p.edit().putString(KEY_SAVED, cur).apply();
            }
            String err = writeGovernor("performance");
            return err == null
                    ? "已切 performance（原 " + cur + "，已记账）"
                    : "切换失败：" + err;
        } catch (Throwable t) {
            return "失败：" + t.getClass().getSimpleName();
        }
    }

    /**
     * 还原 governor。会话结束与 App 启动各调一次。
     *
     * <p>没有记账就直接返回：绝大多数会话根本没改过，不该产生任何写入。
     */
    public static String restore(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = p.getString(KEY_SAVED, null);
            if (saved == null || saved.isEmpty()) return "无待还原项";

            String cur = governor();
            if (saved.equals(cur)) {
                p.edit().remove(KEY_SAVED).apply();
                return "已是 " + saved + "，清账即可";
            }
            if (!rootGranted()) return "还原失败：su 未授权";
            String err = writeGovernor(saved);
            if (err != null) return "还原失败：" + err;
            p.edit().remove(KEY_SAVED).apply();
            return "已还原 " + saved;
        } catch (Throwable t) {
            // Direct Boot 下读凭据加密存储会抛 IllegalStateException —— 0.3.20 的崩溃
            // 就是这么来的。这里不能再踩一次：兜底不了就安静放弃，下次启动还有机会。
            return "跳过（存储不可用：" + t.getClass().getSimpleName() + "）";
        }
    }

    /** 上一次会话有没有留下未还原的改动 —— 启动时决定要不要打日志。 */
    public static boolean hasPendingRestore(Context ctx) {
        try {
            String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SAVED, null);
            return saved != null && !saved.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 执行 ──────────────────────────────────────────────────────────────

    /**
     * 逐核写 governor，最后读回 cpu0 验证。
     *
     * <p>online 核之外写失败很正常（cpu7 可能离线），所以错误全部丢进 /dev/null，
     * 只用 cpu0 的回读结果做判定 —— 它是唯一保证在线的核。
     *
     * @return null 表示成功，否则返回失败原因
     */
    private static String writeGovernor(String target) {
        StringBuilder cmd = new StringBuilder(256);
        for (int i = 0; i < MAX_CORES; i++) {
            cmd.append("echo ").append(target)
                    .append(" > ").append(CPU_SYS).append("/cpu").append(i)
                    .append("/cpufreq/scaling_governor 2>/dev/null; ");
        }
        cmd.append("cat ").append(CPU_SYS).append("/cpu0/cpufreq/scaling_governor");
        String out = exec(cmd.toString());
        if (out == null) return "su 执行失败";
        String got = out.trim();
        return target.equals(got) ? null : "回读为 " + (got.isEmpty() ? "(空)" : got);
    }

    /** 在 su 下跑一条命令并取回 stdout。未授权或异常返回 null。 */
    public static String exec(String cmd) {
        if (!rootGranted()) return null;
        try {
            List<String> out = Shell.cmd(cmd).exec().getOut();
            StringBuilder sb = new StringBuilder(256);
            for (String line : out) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "exec 失败: " + cmd, t);
            return null;
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
