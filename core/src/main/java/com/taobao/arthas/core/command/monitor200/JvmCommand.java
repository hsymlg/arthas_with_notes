package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.model.JvmModel;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Summary;

import java.lang.management.*;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM info command
 *
 * @author vlinux on 15/6/6.
 */
// 使用 @Name 注解指定命令名称为 "jvm"
@Name("jvm")
// 使用 @Summary 注解提供命令的简要描述
@Summary("Display the target JVM information")
// 使用 @Description 注解提供命令的详细描述，包含文档链接
@Description(Constants.WIKI + Constants.WIKI_HOME + "jvm")
// 继承 AnnotatedCommand 类，这是一个抽象类，需要实现 process 方法
public class JvmCommand extends AnnotatedCommand {

    // ManagementFactory相关指标的应用（https://www.alibabacloud.com/help/zh/arms/application-monitoring/developer-reference/jvm-metrics）
    // JVM内存监控（https://www.alibabacloud.com/help/zh/arms/application-monitoring/developer-reference/jvm-monitoring-memory-details?spm=a2c63.p38356.0.i0）
    // 获取运行时管理 Bean，用于获取 JVM 的运行时信息
    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    // 获取类加载管理 Bean，用于获取类加载相关信息
    private final ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
    // 获取编译管理 Bean，用于获取 JVM 编译相关信息
    private final CompilationMXBean compilationMXBean = ManagementFactory.getCompilationMXBean();
    // 获取垃圾回收器管理 Bean 集合，用于获取垃圾回收器的相关信息
    private final Collection<GarbageCollectorMXBean> garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
    // 获取内存管理器管理 Bean 集合，用于获取内存管理器的相关信息
    private final Collection<MemoryManagerMXBean> memoryManagerMXBeans = ManagementFactory.getMemoryManagerMXBeans();
    // 获取内存管理 Bean，用于获取 JVM 内存使用情况
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    // 获取操作系统管理 Bean，用于获取操作系统相关信息
    private final OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    // 获取线程管理 Bean，用于获取线程相关信息
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /**
     * 处理命令的核心方法，当命令被执行时会调用该方法
     * 接口作为方法参数是 Java 中实现多态和设计模式的核心机制，常用于回调、策略模式、事件监听、依赖注入等场景。
     *
     * @param process 命令处理对象，用于添加结果和结束命令处理
     */
    @Override
    public void process(CommandProcess process) {
        // 创建一个 JvmModel 对象，用于存储收集到的 JVM 信息
        JvmModel jvmModel = new JvmModel();

        // 调用 addRuntimeInfo 方法，将运行时信息添加到 jvmModel 中
        addRuntimeInfo(jvmModel);

        // 调用 addClassLoading 方法，将类加载信息添加到 jvmModel 中
        addClassLoading(jvmModel);

        // 调用 addCompilation 方法，将编译信息添加到 jvmModel 中
        addCompilation(jvmModel);

        // 如果存在垃圾回收器管理 Bean，则调用 addGarbageCollectors 方法，将垃圾回收器信息添加到 jvmModel 中
        if (!garbageCollectorMXBeans.isEmpty()) {
            addGarbageCollectors(jvmModel);
        }

        // 如果存在内存管理器管理 Bean，则调用 addMemoryManagers 方法，将内存管理器信息添加到 jvmModel 中
        if (!memoryManagerMXBeans.isEmpty()) {
            addMemoryManagers(jvmModel);
        }

        // 调用 addMemory 方法，将内存使用信息添加到 jvmModel 中
        addMemory(jvmModel);

        // 调用 addOperatingSystem 方法，将操作系统信息添加到 jvmModel 中
        addOperatingSystem(jvmModel);

        // 调用 addThread 方法，将线程信息添加到 jvmModel 中
        addThread(jvmModel);

        // 调用 addFileDescriptor 方法，将文件描述符信息添加到 jvmModel 中
        addFileDescriptor(jvmModel);

        // 将 jvmModel 添加到命令处理结果中
        process.appendResult(jvmModel);
        // 结束命令处理
        process.end();
    }

    /**
     * 添加文件描述符信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addFileDescriptor(JvmModel jvmModel) {
        // 定义文件描述符信息的分组名称
        String group = "FILE-DESCRIPTOR";
        // 调用 invokeFileDescriptor 方法获取最大文件描述符数量，并添加到 jvmModel 中
        jvmModel.addItem(group,"MAX-FILE-DESCRIPTOR-COUNT", invokeFileDescriptor(operatingSystemMXBean, "getMaxFileDescriptorCount"))
                // 调用 invokeFileDescriptor 方法获取当前打开的文件描述符数量，并添加到 jvmModel 中
                .addItem(group,"OPEN-FILE-DESCRIPTOR-COUNT", invokeFileDescriptor(operatingSystemMXBean, "getOpenFileDescriptorCount"));
    }

    /**
     * 通过反射调用操作系统管理 Bean 的方法获取文件描述符信息
     *
     * @param os   操作系统管理 Bean
     * @param name 要调用的方法名
     * @return 方法调用的结果，如果出现异常则返回 -1
     */
    private long invokeFileDescriptor(OperatingSystemMXBean os, String name) {
        try {
            // 获取指定名称的方法对象
            final Method method = os.getClass().getDeclaredMethod(name);
            // 设置方法可访问，即使是私有方法也能调用
            method.setAccessible(true);
            // 调用方法并返回结果
            return (Long) method.invoke(os);
        } catch (Exception e) {
            // 如果出现异常，返回 -1
            return -1;
        }
    }

    /**
     * 添加运行时信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addRuntimeInfo(JvmModel jvmModel) {
        // 初始化启动类路径为空字符串
        String bootClassPath = "";
        try {
            // 尝试获取启动类路径
            bootClassPath = runtimeMXBean.getBootClassPath();
        } catch (Exception e) {
            // 在 JDK 9 及以上版本可能会抛出 UnsupportedOperationException，忽略该异常
            // under jdk9 will throw UnsupportedOperationException, ignore
        }
        // 定义运行时信息的分组名称
        String group = "RUNTIME";
        // 添加机器名称到 jvmModel 中
        jvmModel.addItem(group,"MACHINE-NAME", runtimeMXBean.getName());
        // 添加 JVM 启动时间到 jvmModel 中，格式化时间为 "yyyy-MM-dd HH:mm:ss"
        jvmModel.addItem(group, "JVM-START-TIME", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(runtimeMXBean.getStartTime())));
        // 添加管理规范版本到 jvmModel 中
        jvmModel.addItem(group, "MANAGEMENT-SPEC-VERSION", runtimeMXBean.getManagementSpecVersion());
        // 添加规范名称到 jvmModel 中
        jvmModel.addItem(group, "SPEC-NAME", runtimeMXBean.getSpecName());
        // 添加规范供应商到 jvmModel 中
        jvmModel.addItem(group, "SPEC-VENDOR", runtimeMXBean.getSpecVendor());
        // 添加规范版本到 jvmModel 中
        jvmModel.addItem(group, "SPEC-VERSION", runtimeMXBean.getSpecVersion());
        // 添加 JVM 名称到 jvmModel 中
        jvmModel.addItem(group, "VM-NAME", runtimeMXBean.getVmName());
        // 添加 JVM 供应商到 jvmModel 中
        jvmModel.addItem(group, "VM-VENDOR", runtimeMXBean.getVmVendor());
        // 添加 JVM 版本到 jvmModel 中
        jvmModel.addItem(group, "VM-VERSION", runtimeMXBean.getVmVersion());
        // 添加输入参数到 jvmModel 中
        jvmModel.addItem(group, "INPUT-ARGUMENTS", runtimeMXBean.getInputArguments());
        // 添加类路径到 jvmModel 中
        jvmModel.addItem(group, "CLASS-PATH", runtimeMXBean.getClassPath());
        // 添加启动类路径到 jvmModel 中
        jvmModel.addItem(group, "BOOT-CLASS-PATH", bootClassPath);
        // 添加库路径到 jvmModel 中
        jvmModel.addItem(group, "LIBRARY-PATH", runtimeMXBean.getLibraryPath());
    }

    /**
     * 添加类加载信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addClassLoading(JvmModel jvmModel) {
        // 定义类加载信息的分组名称
        String group = "CLASS-LOADING";
        // 添加当前加载的类数量到 jvmModel 中
        jvmModel.addItem(group, "LOADED-CLASS-COUNT", classLoadingMXBean.getLoadedClassCount());
        // 添加总共加载的类数量到 jvmModel 中
        jvmModel.addItem(group, "TOTAL-LOADED-CLASS-COUNT", classLoadingMXBean.getTotalLoadedClassCount());
        // 添加卸载的类数量到 jvmModel 中
        jvmModel.addItem(group, "UNLOADED-CLASS-COUNT", classLoadingMXBean.getUnloadedClassCount());
        // 添加类加载是否开启详细日志信息到 jvmModel 中
        jvmModel.addItem(group, "IS-VERBOSE", classLoadingMXBean.isVerbose());
    }

    /**
     * 添加编译信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addCompilation(JvmModel jvmModel) {
        // 如果编译管理 Bean 为空，直接返回
        if (compilationMXBean == null) {
            return;
        }
        // 定义编译信息的分组名称
        String group = "COMPILATION";
        // 添加编译器名称到 jvmModel 中
        jvmModel.addItem(group, "NAME", compilationMXBean.getName());
        // 如果支持编译时间监控
        if (compilationMXBean.isCompilationTimeMonitoringSupported()) {
            // 添加总编译时间到 jvmModel 中，并注明单位为毫秒
            jvmModel.addItem(group, "TOTAL-COMPILE-TIME", compilationMXBean.getTotalCompilationTime(), "time (ms)");
        }
    }

    /**
     * 添加垃圾回收器信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addGarbageCollectors(JvmModel jvmModel) {
        // 定义垃圾回收器信息的分组名称
        String group = "GARBAGE-COLLECTORS";
        // 遍历垃圾回收器管理 Bean 集合
        for (GarbageCollectorMXBean gcMXBean : garbageCollectorMXBeans) {
            // 创建一个 LinkedHashMap 用于存储垃圾回收器信息
            Map<String, Object> gcInfo = new LinkedHashMap<String, Object>();
            // 添加垃圾回收器名称到 gcInfo 中
            gcInfo.put("name", gcMXBean.getName());
            // 添加垃圾回收次数到 gcInfo 中
            gcInfo.put("collectionCount", gcMXBean.getCollectionCount());
            // 添加垃圾回收总时间到 gcInfo 中
            gcInfo.put("collectionTime", gcMXBean.getCollectionTime());

            // 将垃圾回收器信息添加到 jvmModel 中，并注明单位为次数/毫秒
            jvmModel.addItem(group, gcMXBean.getName(), gcInfo, "count/time (ms)");
        }
    }

    /**
     * 添加内存管理器信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addMemoryManagers(JvmModel jvmModel) {
        // 定义内存管理器信息的分组名称
        String group = "MEMORY-MANAGERS";
        // 遍历内存管理器管理 Bean 集合
        for (final MemoryManagerMXBean memoryManagerMXBean : memoryManagerMXBeans) {
            // 如果内存管理器有效
            if (memoryManagerMXBean.isValid()) {
                // 获取内存管理器名称，如果有效则使用原名称，否则在名称后面加上 "(Invalid)"
                final String name = memoryManagerMXBean.isValid()
                        ? memoryManagerMXBean.getName()
                        : memoryManagerMXBean.getName() + "(Invalid)";
                // 将内存管理器名称和对应的内存池名称添加到 jvmModel 中
                jvmModel.addItem(group, name, memoryManagerMXBean.getMemoryPoolNames());
            }
        }
    }

    /**
     * 添加内存使用信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addMemory(JvmModel jvmModel) {
        // 定义内存信息的分组名称
        String group = "MEMORY";
        // 获取堆内存使用情况
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        // 调用 getMemoryUsageInfo 方法获取堆内存使用信息
        Map<String, Object> heapMemoryInfo = getMemoryUsageInfo("heap", heapMemoryUsage);
        // 将堆内存使用信息添加到 jvmModel 中，并注明单位为字节
        jvmModel.addItem(group, "HEAP-MEMORY-USAGE", heapMemoryInfo, "memory in bytes");

        // 获取非堆内存使用情况
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        // 调用 getMemoryUsageInfo 方法获取非堆内存使用信息
        Map<String, Object> nonheapMemoryInfo = getMemoryUsageInfo("nonheap", nonHeapMemoryUsage);
        // 将非堆内存使用信息添加到 jvmModel 中，并注明单位为字节
        jvmModel.addItem(group,"NO-HEAP-MEMORY-USAGE", nonheapMemoryInfo, "memory in bytes");

        // 添加等待终结的对象数量到 jvmModel 中
        jvmModel.addItem(group,"PENDING-FINALIZE-COUNT", memoryMXBean.getObjectPendingFinalizationCount());
    }

    /**
     * 获取内存使用信息
     *
     * @param name           内存类型名称，如 "heap" 或 "nonheap"
     * @param heapMemoryUsage 内存使用情况对象
     * @return 包含内存使用信息的 Map
     */
    private Map<String, Object> getMemoryUsageInfo(String name, MemoryUsage heapMemoryUsage) {
        // 创建一个 LinkedHashMap 用于存储内存使用信息
        Map<String, Object> memoryInfo = new LinkedHashMap<String, Object>();
        // 添加内存类型名称到 memoryInfo 中
        memoryInfo.put("name", name);
        // 添加初始内存大小到 memoryInfo 中
        memoryInfo.put("init", heapMemoryUsage.getInit());
        // 添加已使用内存大小到 memoryInfo 中
        memoryInfo.put("used", heapMemoryUsage.getUsed());
        // 添加已提交内存大小到 memoryInfo 中
        memoryInfo.put("committed", heapMemoryUsage.getCommitted());
        // 添加最大内存大小到 memoryInfo 中
        memoryInfo.put("max", heapMemoryUsage.getMax());
        // 返回包含内存使用信息的 Map
        return memoryInfo;
    }

    /**
     * 添加操作系统信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addOperatingSystem(JvmModel jvmModel) {
        // 定义操作系统信息的分组名称
        String group = "OPERATING-SYSTEM";
        // 添加操作系统名称到 jvmModel 中
        jvmModel.addItem(group,"OS", operatingSystemMXBean.getName())
                // 添加操作系统架构到 jvmModel 中
                .addItem(group,"ARCH", operatingSystemMXBean.getArch())
                // 添加可用处理器数量到 jvmModel 中
                .addItem(group,"PROCESSORS-COUNT", operatingSystemMXBean.getAvailableProcessors())
                // 添加系统负载平均值到 jvmModel 中
                .addItem(group,"LOAD-AVERAGE", operatingSystemMXBean.getSystemLoadAverage())
                // 添加操作系统版本到 jvmModel 中
                .addItem(group,"VERSION", operatingSystemMXBean.getVersion());
    }

    /**
     * 添加线程信息到 JvmModel 中
     *
     * @param jvmModel 存储 JVM 信息的模型对象
     */
    private void addThread(JvmModel jvmModel) {
        // 定义线程信息的分组名称
        String group = "THREAD";
        // 添加当前线程数量到 jvmModel 中
        jvmModel.addItem(group, "COUNT", threadMXBean.getThreadCount())
                // 添加守护线程数量到 jvmModel 中
                .addItem(group, "DAEMON-COUNT", threadMXBean.getDaemonThreadCount())
                // 添加线程峰值数量到 jvmModel 中
                .addItem(group, "PEAK-COUNT", threadMXBean.getPeakThreadCount())
                // 添加总共启动的线程数量到 jvmModel 中
                .addItem(group, "STARTED-COUNT", threadMXBean.getTotalStartedThreadCount())
                // 添加死锁线程数量到 jvmModel 中
                .addItem(group, "DEADLOCK-COUNT",getDeadlockedThreadsCount(threadMXBean));
    }

    /**
     * 获取死锁线程的数量
     *
     * @param threads 线程管理 Bean
     * @return 死锁线程的数量，如果没有死锁线程则返回 0
     */
    private int getDeadlockedThreadsCount(ThreadMXBean threads) {
        // 调用 findDeadlockedThreads 方法获取死锁线程的 ID 数组
        final long[] ids = threads.findDeadlockedThreads();
        // 如果 ID 数组为空，说明没有死锁线程，返回 0
        if (ids == null) {
            return 0;
        } else {
            // 否则返回 ID 数组的长度，即死锁线程的数量
            return ids.length;
        }
    }
}