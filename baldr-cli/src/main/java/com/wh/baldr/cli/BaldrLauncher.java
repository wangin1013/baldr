package com.wh.baldr.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Baldr 自举启动器。
 *
 * <p>Baldr 通过 {@code ByteBuddyAgent.install()} 在运行期动态 attach Arthas，
 * 会触发若干由 JVM / async-profiler native 层直接写文件描述符 fd=2 的警告，
 * 这些警告绕过 Java 的 {@code System.err}，无法在 Java 代码内拦截。</p>
 *
 * <p>为了让「IDE Run / java -jar / 脚本」等所有启动方式都获得干净输出，
 * 本类在真正业务逻辑执行前做一次进程自重启（self-relaunch）：
 * <ol>
 *   <li>检测当前 JVM 是否已带消除警告所需参数，若已带则直接放行；</li>
 *   <li>否则以相同 classpath、追加 {@code -XX:+EnableDynamicAgentLoading}、
 *       {@code -Xshare:off} 及重启标志，fork 一个新的 java 子进程；</li>
 *   <li>子进程的 stderr 经逐行过滤，剔除已知的 native 层警告后再输出；</li>
 *   <li>原进程等待子进程结束并以其退出码退出。</li>
 * </ol>
 *
 * @author rubant
 * @date 2026-08-16
 */
final class BaldrLauncher {

    /** 重启标志：子进程带此系统属性，避免无限重启 */
    private static final String RELAUNCH_FLAG = "baldr.relaunched";

    /** 需要过滤的 native 层警告行片段（兜底，正常情况下 JVM 参数已消除前几条） */
    private static final String[] SUPPRESSED = {
            "A Java agent has been loaded dynamically",
            "please run with -XX:+EnableDynamicAgentLoading",
            "please run with -Djdk.instrument.traceUsage",
            "Dynamic loading of agents will be disallowed",
            "Sharing is only supported for boot loader classes",
            "[WARN] Unknown argument: framebuf"
    };

    private BaldrLauncher() {
    }

    /**
     * 若尚未重启，则带干净参数 fork 子进程并接管其输出，随后以子进程退出码结束当前进程。
     * 若已是重启后的子进程，则直接返回，交由调用方继续执行业务逻辑。
     *
     * @param args 原始命令行参数
     * @return true 表示当前已是重启后的子进程，应继续执行业务逻辑；
     *         false 情况下本方法不会返回（原进程会在此退出）
     */
    static boolean relaunchIfNeeded(String[] args) {
        if (Boolean.getBoolean(RELAUNCH_FLAG)) {
            // 已是重启后的子进程，放行
            return true;
        }

        try {
            List<String> command = buildCommand(args);
            ProcessBuilder pb = new ProcessBuilder(command);
            // stdin 直接继承；stdout / stderr 均走管道过滤。
            // 注意：async-profiler 的 framebuf 警告经 Arthas 调用后混入 stdout，
            // 故 stdout 也需过滤（仅剔除已知警告行，报告等正常内容原样输出）。
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);

            Process child = pb.start();
            Thread outPump = new Thread(
                    () -> filterStream(child.getInputStream(), System.out), "baldr-stdout-filter");
            Thread errPump = new Thread(
                    () -> filterStream(child.getErrorStream(), System.err), "baldr-stderr-filter");
            outPump.setDaemon(true);
            errPump.setDaemon(true);
            outPump.start();
            errPump.start();

            int code = child.waitFor();
            outPump.join(2000);
            errPump.join(2000);
            System.exit(code);
            return false; // 不会执行到
        } catch (Exception e) {
            // 自重启失败则降级：直接在当前进程执行，警告可能出现但功能不受影响
            System.err.println("[baldr] self-relaunch failed, running in-place: " + e.getMessage());
            return true;
        }
    }

    /**
     * 构造子进程启动命令：java + 消警参数 + 相同 classpath + 主类 + 原始参数。
     */
    private static List<String> buildCommand(String[] args) {
        String javaBin = System.getProperty("java.home")
                + File.separator + "bin" + File.separator + "java";

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-XX:+EnableDynamicAgentLoading");
        command.add("-Xshare:off");
        command.add("-D" + RELAUNCH_FLAG + "=true");

        // 继承原 JVM 的自定义启动参数（如 -Dxxx、-Xmx），但剔除会冲突的重复项
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-agentlib:")
                    || jvmArg.startsWith("-javaagent:")
                    || jvmArg.startsWith("-Xshare:")
                    || jvmArg.contains("EnableDynamicAgentLoading")) {
                continue;
            }
            command.add(jvmArg);
        }

        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(BaldrCli.class.getName());

        for (String arg : args) {
            command.add(arg);
        }
        return command;
    }

    /**
     * 逐行读取子进程输出流，剔除已知 native 层警告后转发到指定目标流。
     *
     * @param source 子进程的 stdout 或 stderr
     * @param target 当前进程对应的输出流（System.out / System.err）
     */
    private static void filterStream(InputStream source, java.io.PrintStream target) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!isSuppressed(line)) {
                    target.println(line);
                }
            }
        } catch (IOException ignored) {
            // 子进程结束时流关闭，忽略
        }
    }

    private static boolean isSuppressed(String line) {
        for (String pattern : SUPPRESSED) {
            if (line.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}