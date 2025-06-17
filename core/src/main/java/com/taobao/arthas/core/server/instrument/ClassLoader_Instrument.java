package com.taobao.arthas.core.server.instrument;

import com.alibaba.bytekit.agent.inst.Instrument;
import com.alibaba.bytekit.agent.inst.InstrumentApi;

/**
 * 对java.lang.ClassLoader类的loadClass方法进行增强的类
 *
 * 该类使用ByteKit框架的@Instrument注解，会在运行时动态修改ClassLoader的字节码
 * 主要目的是确保Arthas相关类（以"java.arthas."开头）被正确的类加载器加载
 *
 * @see java.lang.ClassLoader#loadClass(String)
 * @author hengyunabc 2020-11-30
 */
@Instrument(Class = "java.lang.ClassLoader")
public abstract class ClassLoader_Instrument {
    /**
     * 增强后的loadClass方法
     * 当加载以"java.arthas."开头的类时，会优先使用扩展类加载器(ExtClassLoader)加载
     * 这样可以确保Arthas相关类被正确加载，避免类加载冲突
     *
     * @param name 要加载的类的全限定名
     * @return 加载的Class对象
     * @throws ClassNotFoundException 类不存在时抛出异常
     */
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // 检查是否是Arthas相关类
        if (name.startsWith("java.arthas.")) {
            // 获取系统类加载器的父类加载器，通常是扩展类加载器(ExtClassLoader)
            ClassLoader extClassLoader = ClassLoader.getSystemClassLoader().getParent();
            // 如果扩展类加载器存在，则使用它来加载Arthas相关类
            if (extClassLoader != null) {
                return extClassLoader.loadClass(name);
            }
        }

        // 对于非Arthas相关类，调用原始的loadClass方法进行加载
        // InstrumentApi.invokeOrigin()会调用被增强方法的原始实现
        Class clazz = InstrumentApi.invokeOrigin();
        return clazz;
    }
}