package com.wh.baldr.core.arthas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * 内联自 arthas-agent-attach 3.7.2，原始作者 hengyunabc。
 * 将 Arthas attach 到当前 JVM，暴露 HTTP API 供 profiler 采样使用。
 *
 * <p>与原版差异：
 * <ul>
 *   <li>包名从 {@code com.taobao.arthas.agent.attach} 迁移至 {@code com.wh.baldr.core.arthas}</li>
 *   <li>移除 zt-zip 依赖，使用自实现的解压工具替代 {@code ZipUtil.unpack}</li>
 *   <li>移除 arthas-spy 编译期依赖，SpyAPI 检测改为反射调用</li>
 * </ul>
 *
 * @author hengyunabc (original)
 * @date 2020-06-22 (original)
 */
public class ArthasAgent {
    private static final int TEMP_DIR_ATTEMPTS = 10000;

    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";

    private String errorMessage;

    private Map<String, String> configMap = new HashMap<String, String>();
    private String arthasHome;
    private boolean slientInit;
    private Instrumentation instrumentation;

    public ArthasAgent() {
        this(null, null, false, null);
    }

    public ArthasAgent(Map<String, String> configMap) {
        this(configMap, null, false, null);
    }

    public ArthasAgent(String arthasHome) {
        this(null, arthasHome, false, null);
    }

    public ArthasAgent(Map<String, String> configMap, String arthasHome, boolean slientInit,
            Instrumentation instrumentation) {
        if (configMap != null) {
            this.configMap = configMap;
        }

        this.arthasHome = arthasHome;
        this.slientInit = slientInit;
        this.instrumentation = instrumentation;
    }

    public static void attach() {
        new ArthasAgent().init();
    }

    /**
     * @see <a href="https://arthas.aliyun.com/doc/arthas-properties.html">Arthas Properties</a>
     */
    public static void attach(Map<String, String> configMap) {
        new ArthasAgent(configMap).init();
    }

    /**
     * 使用指定的 arthasHome 目录。
     *
     * @param arthasHome arthas 目录
     */
    public static void attach(String arthasHome) {
        new ArthasAgent(arthasHome).init();
    }

    public void init() throws IllegalStateException {
        // 尝试判断 arthas 是否已在运行，如果是的话，直接就退出
        if (isArthasAlreadyRunning()) {
            return;
        }

        try {
            if (instrumentation == null) {
                instrumentation = ByteBuddyAgent.install();
            }

            // 检查 arthasHome
            if (arthasHome == null || arthasHome.trim().isEmpty()) {
                // 解压出 arthasHome
                URL coreJarUrl = this.getClass().getClassLoader().getResource("arthas-bin.zip");
                if (coreJarUrl != null) {
                    File tempArthasDir = createTempDir();
                    unzip(coreJarUrl.openStream(), tempArthasDir);
                    arthasHome = tempArthasDir.getAbsolutePath();
                } else {
                    throw new IllegalArgumentException("can not getResources arthas-bin.zip from classloader: "
                            + this.getClass().getClassLoader());
                }
            }

            // find arthas-core.jar
            File arthasCoreJarFile = new File(arthasHome, ARTHAS_CORE_JAR);
            if (!arthasCoreJarFile.exists()) {
                throw new IllegalStateException("can not find arthas-core.jar under arthasHome: " + arthasHome);
            }
            AttachArthasClassloader arthasClassLoader = new AttachArthasClassloader(
                    new URL[] { arthasCoreJarFile.toURI().toURL() });

            /**
             * <pre>
             * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(inst);
             * </pre>
             */
            Class<?> bootstrapClass = arthasClassLoader.loadClass(ARTHAS_BOOTSTRAP);
            Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, Instrumentation.class, Map.class).invoke(null,
                    instrumentation, configMap);
            boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
            if (!isBind) {
                String errorMsg = "Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.";
                throw new RuntimeException(errorMsg);
            }
        } catch (Throwable e) {
            errorMessage = e.getMessage();
            if (!slientInit) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * 通过反射检测 Arthas SpyAPI 是否已初始化，避免编译期依赖 arthas-spy。
     */
    private boolean isArthasAlreadyRunning() {
        try {
            Class<?> spyClass = Class.forName("java.arthas.SpyAPI");
            Object inited = spyClass.getMethod("isInited").invoke(null);
            return Boolean.TRUE.equals(inited);
        } catch (Throwable e) {
            // 加载不到说明未运行
            return false;
        }
    }

    private static File createTempDir() {
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = "arthas-" + System.currentTimeMillis() + "-";

        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create directory within " + TEMP_DIR_ATTEMPTS + " attempts (tried "
                + baseName + "0 to " + baseName + (TEMP_DIR_ATTEMPTS - 1) + ')');
    }

    /**
     * 自实现的 ZIP 解压，替代 zt-zip 的 {@code ZipUtil.unpack}。
     */
    public static void unzip(InputStream in, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    throw new IOException("Illegal zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("Failed to create dir: " + out);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create dir: " + parent);
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        copyStream(zis, os);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            os.write(buf, 0, n);
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}