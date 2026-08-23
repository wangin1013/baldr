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
 *   <li>否则以相同 classpath、追加 {@code -XX:+EnableDynamicAgentLoading}（JDK 9+）、
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

    /** JDK 9+ 特有的动态 agent 加载 / CDS 警告（兜底，正常情况下 JVM 参数已消除） */
    private static final String[] SUPPRESSED_JDK9 = {
            "A Java agent has been loaded dynamically",
            "please run with -XX:+EnableDynamicAgentLoading",
            "please run with -Djdk.instrument.traceUsage",
            "Dynamic loading of agents will be disallowed",
            "Sharing is only supported for boot loader classes"
    };

    /** 所有 JDK 版本均可能出现的 async-profiler native 层警告 */
    private static final String[] SUPPRESSED_COMMON = {
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
        // 是否加 -XX:+EnableDynamicAgentLoading 以「实际执行子进程的那个 java」是否支持为准，
        // 而非父进程的版本号——避免 java.home 指向的 JDK 与父进程版本判断错配时，
        // 传入不被识别的 VM 参数（JDK 8 会因此直接启动失败）。
        if (supportsVmOption(javaBin, "-XX:+EnableDynamicAgentLoading")) {
            command.add("-XX:+EnableDynamicAgentLoading");
        }
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
        for (String pattern : SUPPRESSED_COMMON) {
            if (line.contains(pattern)) {
                return true;
            }
        }
        if (isJdk9OrLater()) {
            for (String pattern : SUPPRESSED_JDK9) {
                if (line.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检测指定 java 可执行文件是否识别某个 VM 参数。
     *
     * <p>以 {@code javaBin <option> -version} 试运行为准：退出码为 0 表示支持。
     * 这样加不加 {@code -XX:+EnableDynamicAgentLoading} 取决于「真正执行子进程的
     * 那个 java」，而非父进程的版本号，可避免 JDK 版本错配时传入不被识别的参数
     * 导致子进程启动失败（{@code Unrecognized VM option}）。</p>
     *
     * @param javaBin java 可执行文件绝对路径
     * @param option  待检测的 VM 参数，如 {@code -XX:+EnableDynamicAgentLoading}
     * @return true 表示该 java 识别此参数
     */
    private static boolean supportsVmOption(String javaBin, String option) {
        try {
            Process p = new ProcessBuilder(javaBin, option, "-version")
                    .redirectErrorStream(true)
                    .start();
            // 读空子进程输出，避免管道缓冲区写满导致其阻塞
            drain(p.getInputStream());
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            // 探测失败时保守地视为不支持，宁可保留警告也不让子进程启动失败
            return false;
        }
    }

    /** 读空并丢弃一个输入流的全部内容。 */
    private static void drain(InputStream in) {
        try {
            byte[] buf = new byte[1024];
            while (in.read(buf) != -1) {
                // 丢弃
            }
        } catch (IOException ignored) {
            // 流结束/关闭，忽略
        }
    }

    /**
     * 检测当前 JVM 是否为 JDK 9 或更高版本。
     * JDK 9 开始支持 {@code -XX:+EnableDynamicAgentLoading} 参数，
     * JDK 8 不识别该参数会导致 JVM 启动失败。
     */
    private static boolean isJdk9OrLater() {
        try {
            String specVersion = System.getProperty("java.specification.version");
            // JDK 8 及之前版本格式为 "1.x"（如 "1.8"），JDK 9+ 直接为主版本号（如 "9"、"11"、"17"）
            if (specVersion != null && specVersion.startsWith("1.")) {
                return false;
            }
            return true;
        } catch (Exception e) {
            // 无法获取版本信息时保守地不加该参数
            return false;
        }
    }
}