package com.taobao.arthas.core.advisor;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 类文件转换器管理器
 *
 * 核心功能：
 * 1. 统一管理所有ClassFileTransformer实例
 * 2. 按功能分组管理不同类型的转换器（watch/trace/retransform）
 * 3. 提供转换器的注册、移除和销毁功能
 *
 * @see com.taobao.arthas.core.advisor.Enhancer 增强器接口
 * @author hengyunabc 2020-05-18
 */
public class TransformerManager {

    // Java Instrumentation实例，用于注册类文件转换器
    private Instrumentation instrumentation;
    // watch命令相关的转换器列表（线程安全）
    private List<ClassFileTransformer> watchTransformers = new CopyOnWriteArrayList<ClassFileTransformer>();
    // trace命令相关的转换器列表（线程安全）
    private List<ClassFileTransformer> traceTransformers = new CopyOnWriteArrayList<ClassFileTransformer>();
    // 重转换相关的转换器列表（优先级高于watch/trace）
    private List<ClassFileTransformer> reTransformers = new CopyOnWriteArrayList<ClassFileTransformer>();

    // 复合类文件转换器，按顺序调用所有注册的转换器
    private ClassFileTransformer classFileTransformer;

    /**
     * 构造函数，初始化转换器管理器
     *
     * @param instrumentation Java Instrumentation实例
     */
    public TransformerManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;

        // 创建复合转换器，按顺序调用不同类型的转换器
        classFileTransformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                // 先调用重转换转换器（reTransformers）
                for (ClassFileTransformer transformer : reTransformers) {
                    byte[] transformResult = transformer.transform(loader, className, classBeingRedefined,
                            protectionDomain, classfileBuffer);
                    if (transformResult != null) {
                        classfileBuffer = transformResult; // 更新字节码
                    }
                }

                // 再调用watch相关转换器
                for (ClassFileTransformer transformer : watchTransformers) {
                    byte[] transformResult = transformer.transform(loader, className, classBeingRedefined,
                            protectionDomain, classfileBuffer);
                    if (transformResult != null) {
                        classfileBuffer = transformResult;
                    }
                }

                // 最后调用trace相关转换器
                for (ClassFileTransformer transformer : traceTransformers) {
                    byte[] transformResult = transformer.transform(loader, className, classBeingRedefined,
                            protectionDomain, classfileBuffer);
                    if (transformResult != null) {
                        classfileBuffer = transformResult;
                    }
                }

                return classfileBuffer; // 返回最终转换后的字节码
            }
        };
        // 注册复合转换器，true表示重转换已加载的类
        instrumentation.addTransformer(classFileTransformer, true);
    }

    /**
     * 添加watch或trace相关的转换器
     *
     * @param transformer 类文件转换器
     * @param isTracing   是否为trace类型（true为trace，false为watch）
     */
    public void addTransformer(ClassFileTransformer transformer, boolean isTracing) {
        if (isTracing) {
            traceTransformers.add(transformer); // 添加到trace列表
        } else {
            watchTransformers.add(transformer); // 添加到watch列表
        }
    }

    /**
     * 添加重转换相关的转换器（优先级最高）
     *
     * @param transformer 类文件转换器
     */
    public void addRetransformer(ClassFileTransformer transformer) {
        reTransformers.add(transformer);
    }

    /**
     * 移除转换器（从所有列表中移除）
     *
     * @param transformer 要移除的转换器
     */
    public void removeTransformer(ClassFileTransformer transformer) {
        reTransformers.remove(transformer);
        watchTransformers.remove(transformer);
        traceTransformers.remove(transformer);
    }

    /**
     * 销毁转换器管理器，释放资源
     */
    public void destroy() {
        reTransformers.clear(); // 清空所有转换器列表
        watchTransformers.clear();
        traceTransformers.clear();
        instrumentation.removeTransformer(classFileTransformer); // 移除注册的转换器
    }
}