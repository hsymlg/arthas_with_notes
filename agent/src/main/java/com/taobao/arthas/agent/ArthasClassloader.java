package com.taobao.arthas.agent;
// 包声明，指定该类属于Arthas的agent模块

import java.net.URL;
import java.net.URLClassLoader;
// 导入URL和URLClassLoader类，用于自定义类加载器的实现

/**
 * Arthas自定义类加载器，用于隔离Arthas自身类与应用程序类
 * Java 类加载器采用 "双亲委托" 模型，标准层级如下
 * Bootstrap ClassLoader (C++实现)
 *     └── Extension ClassLoader (Java实现)
 *         └── AppClassLoader (Java实现)
 *             └── 应用程序类加载器（可能有多个）
 *
 *
 * @author beiwei30 on 09/12/2016.
 */
public class ArthasClassloader extends URLClassLoader {
    // 继承URLClassLoader，实现自定义类加载逻辑

    /**
     * 构造函数，初始化类加载器
     * 这里ClassLoader.getSystemClassLoader().getParent()获取的是Extension ClassLoader，因此ArthasClassloader的层级关系为：
     * Bootstrap ClassLoader
     *     └── Extension ClassLoader
     *         ├── AppClassLoader
     *         └── ArthasClassloader
     *
     * 类加载器的 "双亲委托" 机制
     * 当类加载器加载类时，会先委托给父加载器处理：
     * 若ArthasClassloader的父加载器是AppClassLoader，则加载 Arthas 自身类时会先让AppClassLoader尝试加载
     * 若应用程序类路径中存在与 Arthas 同名的类，会导致 Arthas 加载自身类失败（加载了应用程序的类）
     *
     * 隔离 Arthas 类与应用类
     * ArthasClassloader 与 AppClassLoader 处于同一层级，彼此无法直接加载对方的类：
     * Arthas 类由 ArthasClassloader 加载
     * 应用类由 AppClassLoader 加载
     * 避免了同名类的加载冲突
     *
     * @param urls 要加载的URL数组（通常指向arthas-core.jar等文件）
     */
    public ArthasClassloader(URL[] urls) {
        // 调用父类URLClassLoader的构造函数
        // 参数1：URL数组，指定类加载器加载类的路径
        // 参数2：父类加载器，设置为系统类加载器的父加载器（通常是扩展类加载器ExtensionClassLoader）
        // 这样设计可确保Arthas类加载器与应用程序类加载器(AppClassLoader)处于同一层级，避免类加载冲突
        super(urls, ClassLoader.getSystemClassLoader().getParent());
    }

    /**
     * 重写loadClass方法，自定义类加载逻辑
     *
     * 在 Java 中，类加载器的核心方法是 loadClass，它实现了 "双亲委托" 模型：
     * 1.先检查类是否已加载
     * 2.再委托给父类加载器加载
     * 3.最后才由自身尝试加载
     *
     * Arthas 重写 loadClass 方法，是为了打破标准的双亲委托模型，实现自定义的类加载策略，解决 Arthas 作为 Java Agent 的类加载冲突问题。
     * ArthasClassloader 颠覆了这一顺序：
     * 1.先尝试自身加载 Arthas 相关类
     * 2.失败时才委托给父加载器
     * 3.对系统类保持标准委托机制
     *
     * @param name 要加载的类名
     * @param resolve 是否解析类（解析会验证类的依赖关系）
     * @return 加载的类对象
     * @throws ClassNotFoundException 类未找到时抛出
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 首先检查类是否已被加载
        final Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass != null) {
            // 若已加载，直接返回
            return loadedClass;
        }

        // 处理系统类（以java.或sun.开头的类）
        if (name != null && (name.startsWith("sun.") || name.startsWith("java."))) {
            // 优先通过父类加载器加载系统类，避免自定义加载导致的ClassNotFoundException
            // 确保JDK核心类，（如java.lang.Object）由标准类加载器加载，由标准类加载器加载，避免与Arthas类冲突
            return super.loadClass(name, resolve);
        }

        try {
            // 尝试通过findClass方法在当前类加载器的URL中查找并加载类
            // findClass方法会遍历当前类加载器的URL数组，尝试在指定的路径中查找类（com.taobao.arthas.agent334.AgentBootstrap.loadOrDefineClassLoader）
            //
            // 假设要加载 com.taobao.arthas.core.server.ArthasBootstrap 类：
            // 1.ArthasClassloader 的 URL 指向 arthas-core.jar
            // 2.findClass 会在 JAR 包中查找 com/taobao/arthas/core/server/ArthasBootstrap.class
            // 3.找到字节码后，通过 defineClass 转换为 Class 对象
            // 4.若未找到，才会委托给父加载器（Extension ClassLoader）
            Class<?> aClass = findClass(name);
            if (resolve) {
                // 若需要解析类，调用resolveClass方法验证类的依赖关系
                resolveClass(aClass);
            }
            return aClass;
        } catch (Exception e) {
            // 加载失败时忽略异常，交给父类加载器处理
            // 这种设计允许Arthas类加载器找不到类时，委托给父加载器继续查找
        }

        // 若当前类加载器无法加载类，委托给父类加载器处理
        return super.loadClass(name, resolve);
    }
}