package com.wh.baldr.core.arthas;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨进程 attach 器：将 Arthas agent 注入到「另一个」目标 JVM。
 *
 * <p>{@link ArthasAgent} 走 {@code ByteBuddyAgent.install()}，只能 attach
 * 到当前 JVM；而 Baldr 作为独立命令行 jar，需要采样的是一个独立的业务 JVM。
 * 本类使用 JVM Attach API（{@code com.sun.tools.attach.VirtualMachine}）
 * 通过目标进程 pid 进行 attach，再 {@code loadAgent} 加载 {@code arthas-agent.jar}，
 * 由其 {@code agentmain} 在目标进程内拉起 {@code ArthasBootstrap} 并暴露 HTTP 端口，
 * 之后 Baldr 侧仍复用 HTTP API 下发 profiler 命令进行采样。</p>
 *
 * <p>agentmain 的参数约定沿用 Arthas 3.x：以 {@code ;} 分隔，首段为
 * {@code arthas-core.jar} 绝对路径，其余为 {@code key=value} 配置项。</p>
 *
 * <p><b>为何用反射 + 运行时挂载 tools.jar：</b>{@code com.sun.tools.attach.VirtualMachine}
 * 在 JDK 8 下位于 {@code $JAVA_HOME/lib/tools.jar}，既不在默认编译 classpath，也不在
 * 运行时应用 classpath；JDK 9+ 才并入 {@code jdk.attach} 模块、默认可用。为此本类：
 * 编译期不 import 该类（避免依赖 tools.jar）；运行期先尝试直接加载（JDK 9+ 命中），
 * 失败则从 {@code $JAVA_HOME/lib/tools.jar} 动态挂载到 URLClassLoader 再加载（JDK 8），
 * 全程反射调用 attach API。这样 JDK 8 与 9+ 均可编译、可运行。</p>
 *
 * @author rubant
 * @date 2026-08-23
 */
public final class ExternalArthasAttacher {

    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    private static final String ARTHAS_AGENT_JAR = "arthas-agent.jar";
    private static final String VM_CLASS = "com.sun.tools.attach.VirtualMachine";

    private ExternalArthasAttacher() {
    }

    /**
     * 将 Arthas agent attach 到指定 pid 的目标 JVM。
     *
     * @param pid        目标业务进程 ID
     * @param arthasHome 已释放的 Arthas home 目录（含 arthas-core.jar / arthas-agent.jar）
     * @param telnetPort telnet 端口
     * @param httpPort   HTTP API 端口
     * @param ip         绑定 IP（通常 127.0.0.1）
     * @param outputPath Arthas/async-profiler 输出目录（统一到 baldr-output，避免生成 arthas-output）
     * @throws Exception attach 失败（进程不存在、权限不足、JDK 版本不兼容等）时抛出
     */
    public static void attach(int pid, String arthasHome, int telnetPort, int httpPort, String ip,
                              String outputPath) throws Exception {
        File home = new File(arthasHome);
        File coreJar = new File(home, ARTHAS_CORE_JAR);
        File agentJar = new File(home, ARTHAS_AGENT_JAR);
        if (!coreJar.isFile()) {
            throw new IllegalStateException("arthas-core.jar not found under arthasHome: " + arthasHome);
        }
        if (!agentJar.isFile()) {
            throw new IllegalStateException("arthas-agent.jar not found under arthasHome: " + arthasHome);
        }

        // agentmain 参数：首段为 arthas-core.jar 绝对路径，其余为 ; 分隔的 key=value
        List<String> args = new ArrayList<>();
        args.add(coreJar.getAbsolutePath());
        args.add("arthas-agent=" + agentJar.getAbsolutePath());
        args.add("arthasHome=" + home.getAbsolutePath());
        args.add("telnetPort=" + telnetPort);
        args.add("httpPort=" + httpPort);
        args.add("ip=" + ip);
        if (outputPath != null && !outputPath.trim().isEmpty()) {
            args.add("outputPath=" + outputPath);
        }
        String agentArgs = String.join(";", args);

        // 反射调用 VirtualMachine.attach(pid) / loadAgent(jar, args) / detach()，
        // 避免编译期依赖 tools.jar（见类注释），运行时按需挂载后由反射加载。
        Class<?> vmClass = loadVmClass();
        Method attach = vmClass.getMethod("attach", String.class);
        Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
        Method detach = vmClass.getMethod("detach");

        Object vm = null;
        try {
            vm = attach.invoke(null, String.valueOf(pid));
            try {
                loadAgent.invoke(vm, agentJar.getAbsolutePath(), agentArgs);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                // JDK 的 loadAgent 只把「空结果」视为成功；而 Arthas 的 agentmain
                // 正常加载后经 attach 通道回传的结果为 "0"，会被 JDK 误判为
                // AgentLoadException("0")。此时 agent 实际已成功注入目标进程，
                // 属已知的跨版本 attach 现象，需当作成功放行。
                if (!isBenignAgentLoadZero(ite.getTargetException())) {
                    throw ite;
                }
            }
        } finally {
            if (vm != null) {
                detach.invoke(vm);
            }
        }
    }

    /**
     * 判断是否为「假异常」：{@code AgentLoadException} 且消息为 {@code "0"}。
     *
     * <p>Arthas agentmain 成功加载后回传 {@code "0"}，被 JDK attach 层误当作
     * 加载失败抛出。这种情况 agent 已实际注入，应视为成功。</p>
     */
    private static boolean isBenignAgentLoadZero(Throwable cause) {
        if (cause == null) {
            return false;
        }
        String name = cause.getClass().getName();
        if (!"com.sun.tools.attach.AgentLoadException".equals(name)) {
            return false;
        }
        String msg = cause.getMessage();
        return msg != null && "0".equals(msg.trim());
    }

    /**
     * 加载 {@code com.sun.tools.attach.VirtualMachine}。
     *
     * <p>JDK 9+ 该类在 {@code jdk.attach} 模块内、系统 classloader 直接可见；
     * JDK 8 需从 {@code $JAVA_HOME/lib/tools.jar} 动态挂载后加载。</p>
     */
    private static Class<?> loadVmClass() throws Exception {
        try {
            // JDK 9+：模块内已提供，直接可加载
            return Class.forName(VM_CLASS);
        } catch (ClassNotFoundException notInClasspath) {
            // JDK 8：尝试从 tools.jar 挂载
            File toolsJar = findToolsJar();
            if (toolsJar == null) {
                throw new IllegalStateException(
                        "无法加载 " + VM_CLASS + "：未在 classpath 找到，也未定位到 tools.jar。"
                                + "请确认使用完整 JDK（而非仅 JRE）运行 Baldr。", notInClasspath);
            }
            URL[] urls = {toolsJar.toURI().toURL()};
            URLClassLoader loader = new URLClassLoader(urls, ExternalArthasAttacher.class.getClassLoader());
            return Class.forName(VM_CLASS, true, loader);
        }
    }

    /**
     * 定位 JDK 8 的 tools.jar：优先 {@code $JAVA_HOME/lib/tools.jar}，
     * 再退回 {@code ${java.home}/../lib/tools.jar}（java.home 常指向 jre 子目录）。
     */
    private static File findToolsJar() {
        List<File> candidates = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            candidates.add(new File(javaHome, "lib/tools.jar"));
            candidates.add(new File(new File(javaHome).getParentFile(), "lib/tools.jar"));
        }
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null) {
            candidates.add(new File(envJavaHome, "lib/tools.jar"));
        }
        for (File f : candidates) {
            if (f != null && f.isFile()) {
                return f;
            }
        }
        return null;
    }
}