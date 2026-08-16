package com.wh.baldr.core.arthas;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 内联自 arthas-agent-attach 3.7.2，原始作者 hengyunabc。
 * Arthas Core 的独立类加载器，优先从 arthas-core.jar 加载类，
 * 避免与宿主应用的类路径冲突。
 *
 * @author hengyunabc (original)
 * @date 2020-06-22 (original)
 */
public class AttachArthasClassloader extends URLClassLoader {
    public AttachArthasClassloader(URL[] urls) {
        super(urls, ClassLoader.getSystemClassLoader().getParent());
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        final Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass != null) {
            return loadedClass;
        }

        // 优先从 parent（SystemClassLoader）里加载系统类，避免抛出 ClassNotFoundException
        if (name != null && (name.startsWith("sun.") || name.startsWith("java."))) {
            return super.loadClass(name, resolve);
        }
        try {
            Class<?> aClass = findClass(name);
            if (resolve) {
                resolveClass(aClass);
            }
            return aClass;
        } catch (Exception e) {
            // ignore
        }
        return super.loadClass(name, resolve);
    }
}