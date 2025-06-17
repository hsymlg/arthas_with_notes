package com.taobao.arthas.core.server;

import java.arthas.SpyAPI;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

import com.alibaba.arthas.deps.ch.qos.logback.classic.LoggerContext;
import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.alibaba.arthas.tunnel.client.TunnelClient;
import com.alibaba.bytekit.asm.instrument.InstrumentConfig;
import com.alibaba.bytekit.asm.instrument.InstrumentParseResult;
import com.alibaba.bytekit.asm.instrument.InstrumentTransformer;
import com.alibaba.bytekit.asm.matcher.SimpleClassMatcher;
import com.alibaba.bytekit.utils.AsmUtils;
import com.alibaba.bytekit.utils.IOUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.ArthasConstants;
import com.taobao.arthas.common.PidUtils;
import com.taobao.arthas.common.SocketUtils;
import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.advisor.TransformerManager;
import com.taobao.arthas.core.command.BuiltinCommandPack;
import com.taobao.arthas.core.command.view.ResultViewResolver;
import com.taobao.arthas.core.config.BinderUtils;
import com.taobao.arthas.core.config.Configure;
import com.taobao.arthas.core.config.FeatureCodec;
import com.taobao.arthas.core.env.ArthasEnvironment;
import com.taobao.arthas.core.env.MapPropertySource;
import com.taobao.arthas.core.env.PropertiesPropertySource;
import com.taobao.arthas.core.env.PropertySource;
import com.taobao.arthas.core.security.SecurityAuthenticator;
import com.taobao.arthas.core.security.SecurityAuthenticatorImpl;
import com.taobao.arthas.core.server.instrument.ClassLoader_Instrument;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.ShellServerOptions;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.arthas.core.shell.handlers.BindHandler;
import com.taobao.arthas.core.shell.history.HistoryManager;
import com.taobao.arthas.core.shell.history.impl.HistoryManagerImpl;
import com.taobao.arthas.core.shell.impl.ShellServerImpl;
import com.taobao.arthas.core.shell.session.SessionManager;
import com.taobao.arthas.core.shell.session.impl.SessionManagerImpl;
import com.taobao.arthas.core.shell.term.impl.HttpTermServer;
import com.taobao.arthas.core.shell.term.impl.http.api.HttpApiHandler;
import com.taobao.arthas.core.shell.term.impl.http.session.HttpSessionManager;
import com.taobao.arthas.core.shell.term.impl.httptelnet.HttpTelnetTermServer;
import com.taobao.arthas.core.util.ArthasBanner;
import com.taobao.arthas.core.util.FileUtils;
import com.taobao.arthas.core.util.IPUtils;
import com.taobao.arthas.core.util.InstrumentationUtils;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.UserStatUtil;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.WildcardMatcher;

import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;


/**
 * Arthas服务器启动类，负责初始化核心组件、启动通信服务并管理生命周期
 * arthas-boot.jar 启动 arthas-core.jar。
 * arthas-core.jar 的 Arthas 类通过 VirtualMachine.loadAgent 将 arthas-agent.jar 加载到目标 JVM。
 * 目标 JVM 执行 AgentBootstrap.agentmain 方法，在该方法中实例化 ArthasBootstrap。（com.taobao.arthas.agent334.AgentBootstrap#agentmain(java.lang.String, java.lang.instrument.Instrumentation)）
 * 虽然 Arthas 类的构造函数中没有直接实例化 ArthasBootstrap，但通过 Attach API 间接触发了它的实例化。
 *
 * @author vlinux on 15/5/2.
 * @author hengyunabc
 */
public class ArthasBootstrap {
    // Arthas Spy工具JAR包名称，用于字节码增强
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    // 系统属性键：Arthas安装目录
    public static final String ARTHAS_HOME_PROPERTY = "arthas.home";
    // Arthas安装目录，全局共享
    private static String ARTHAS_HOME = null;

    // 配置名称属性键
    public static final String CONFIG_NAME_PROPERTY = "arthas.config.name";
    // 配置位置属性键
    public static final String CONFIG_LOCATION_PROPERTY = "arthas.config.location";
    // 配置覆盖所有属性键
    public static final String CONFIG_OVERRIDE_ALL = "arthas.config.overrideAll";

    // 单例实例
    private static ArthasBootstrap arthasBootstrap;

    // Arthas环境配置，管理属性源
    private ArthasEnvironment arthasEnvironment;
    // 核心配置对象，存储启动参数和运行时配置
    private Configure configure;

    // 绑定状态标识，原子操作保证线程安全
    private AtomicBoolean isBindRef = new AtomicBoolean(false);
    // JVM instrumentation接口，用于字节码增强
    private Instrumentation instrumentation;
    // 类加载器增强转换器，用于修改ClassLoader行为
    private InstrumentTransformer classLoaderInstrumentTransformer;
    // 关闭钩子线程
    private Thread shutdown;
    // Shell服务端，处理命令行交互
    private ShellServer shellServer;
    // 定时任务执行器
    private ScheduledExecutorService executorService;
    // 会话管理器，管理客户端连接会话
    private SessionManager sessionManager;
    // 隧道客户端，用于远程通信
    private TunnelClient tunnelClient;

    // 输出文件路径
    private File outputPath;

    // 日志上下文，管理日志配置
    private static LoggerContext loggerContext;
    // Netty工作线程组，处理网络IO
    private EventExecutorGroup workerGroup;

    // 定时器，用于定时任务
    private Timer timer = new Timer("arthas-timer", true);

    // 转换器管理器，管理字节码转换器
    private TransformerManager transformerManager;

    // 结果视图解析器，处理命令结果展示
    private ResultViewResolver resultViewResolver;

    // 命令历史管理器，记录命令历史
    private HistoryManager historyManager;

    // HTTP API处理器，处理HTTP接口请求
    private HttpApiHandler httpApiHandler;

    // HTTP会话管理器，管理HTTP会话
    private HttpSessionManager httpSessionManager;
    // 安全认证器，处理客户端认证
    private SecurityAuthenticator securityAuthenticator;

    /**
     * 构造函数，初始化Arthas服务器
     * @param instrumentation JVM instrumentation接口
     * @param args 启动参数映射
     * @throws Throwable 初始化过程中可能抛出的异常
     */
    private ArthasBootstrap(Instrumentation instrumentation, Map<String, String> args) throws Throwable {
        this.instrumentation = instrumentation;

        // 初始化FastJSON配置，忽略特定错误并设置写入特性
        initFastjson();

        // 1. 初始化Spy工具，用于字节码增强
        initSpy();
        // 2. 初始化Arthas环境配置，加载配置属性
        initArthasEnvironment(args);

        // 获取输出路径配置，若未设置则使用默认路径
        String outputPathStr = configure.getOutputPath();
        if (outputPathStr == null) {
            outputPathStr = ArthasConstants.ARTHAS_OUTPUT;
        }
        // 创建输出目录（若不存在）
        outputPath = new File(outputPathStr);
        outputPath.mkdirs();

        // 3. 初始化日志系统，加载日志配置
        loggerContext = LogUtil.initLogger(arthasEnvironment);

        /**
         * 为什么BootstrapClassLoader已经能加载SpyAPI，还需要增强ClassLoader呢？
         *
         * 场景示例1：OSGi 的 BundleClassLoader
         * 类加载器层级：BundleClassLoader的父类是AppClassLoader，而非BootstrapClassLoader
         * 加载过程：
         * 1.BundleClassLoader尝试加载SpyAPI
         * 2.委托给父类AppClassLoader
         * 3.AppClassLoader委托给ExtClassLoader
         * 4.ExtClassLoader委托给BootstrapClassLoader
         * 5.BootstrapClassLoader成功加载SpyAPI
         * 问题出现：
         * 尽管BootstrapClassLoader加载了SpyAPI，但BundleClassLoader加载的类在引用SpyAPI时可能抛出：
         * java.lang.NoClassDefFoundError: java/arthas/SpyAPI
         *
         * 根本原因：
         * BundleClassLoader在加载类时，可能使用了自定义的类验证逻辑或隔离策略，导致无法正确引用父类加载器中的类
         *
         * 场景示例2：类加载器隔离的典型案例：Tomcat 的 WebappClassLoader
         * Tomcat类加载器层级简化示意：
         * WebappClassLoader (自定义)
         *   └─ Parent: AppClassLoader
         *       └─ Parent: ExtClassLoader
         *           └─ Parent: BootstrapClassLoader
         * 1.Arthas 通过appendToBootstrapClassLoaderSearch将SpyAPI添加到 BootstrapClassLoader
         * 2.Web 应用中的类com.example.Demo需要引用SpyAPI
         * 3.Demo由WebappClassLoader加载，按双亲委派应能访问父类加载的SpyAPI
         * 实际失败原因：
         * Tomcat 的WebappClassLoader重写了loadClass方法，优先加载应用类路径的类，且可能包含：
         * java
         * // Tomcat类加载器中的部分逻辑（简化）
         * @Override
         * public Class<?> loadClass(String name) throws ClassNotFoundException {
         *     // 先检查是否已加载
         *     Class<?> c = findLoadedClass0(name);
         *     if (c != null) {
         *         return c;
         *     }
         *
         *     // 优先从本地类路径加载（打破双亲委派）
         *     c = findClass(name);
         *     if (c != null) {
         *         return c;
         *     }
         *
         *     // 最后才委托给父类
         *     return super.loadClass(name);
         * }
         * 由于SpyAPI不在应用类路径中，findClass失败，委托给父类加载成功，但类验证阶段可能因类加载器来源不同而失败。
         */

        // 4. 增强ClassLoader，解决类加载器中SpyAPI不可见的问题，某些框架（如 OSGi）使用自定义类加载机制，可能需要额外处理
        // 通过enhanceClassLoader()方法修改类加载器的行为，确保SpyAPI可见
        enhanceClassLoader();
        // 5. 初始化基础Bean组件
        initBeans();

        // 6. 启动Agent服务器，绑定通信端口
        bind(configure);

        // 创建定时任务执行器，用于异步执行命令
        executorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                // 创建守护线程，命名为"arthas-command-execute"
                // 守护线程不会阻止 JVM 退出，当所有用户线程结束时自动终止，适合执行非关键的周期性任务（如日志刷新、状态检查）
                final Thread t = new Thread(r, "arthas-command-execute");
                t.setDaemon(true);
                return t;
            }
        });

        // 初始化关闭钩子线程，JVM退出时执行资源释放
        shutdown = new Thread("as-shutdown-hooker") {
            @Override
            public void run() {
                ArthasBootstrap.this.destroy();
            }
        };

        // 初始化转换器管理器，管理字节码转换器
        transformerManager = new TransformerManager(instrumentation);
        // 注册关闭钩子线程
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    /**
     * 初始化FastJSON配置
     */
    private void initFastjson() {
        // 配置FastJSON特性：忽略getter错误、非字符串键作为字符串写入
        JSON.config(JSONWriter.Feature.IgnoreErrorGetter, JSONWriter.Feature.WriteNonStringKeyAsString);
    }

    /**
     * 初始化基础Bean组件
     */
    private void initBeans() {
        // 初始化结果视图解析器
        this.resultViewResolver = new ResultViewResolver();
        // 初始化命令历史管理器
        this.historyManager = new HistoryManagerImpl();
    }

    /**
     * 初始化Spy工具，将Spy添加到BootstrapClassLoader
     *
     * 为什么不直接使用 Arthas 的类加载器？
     * Arthas 的类加载器与应用类加载器是平行关系，相互不可见
     * 若SpyAPI由 Arthas 类加载器加载，应用类无法直接引用
     *
     * 增强代码的执行环境
     * 被增强的应用类在运行时需要直接调用SpyAPI方法
     * 只有当SpyAPI位于 Bootstrap ClassLoader 时，所有类加载器层级才能无冲突地访问它
     *
     * @throws Throwable 初始化过程中可能抛出的异常
     */
    private void initSpy() throws Throwable {
        // 获取系统类加载器的父加载器（通常是BootstrapClassLoader）
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        Class<?> spyClass = null;
        if (parent != null) {
            try {
                // 尝试从父加载器加载SpyAPI类
                spyClass = parent.loadClass("java.arthas.SpyAPI");
            } catch (Throwable e) {
                // 加载失败时忽略异常
            }
        }
        // 若SpyAPI类未加载成功
        if (spyClass == null) {
            // 获取当前类的代码源
            CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                // 获取arthas-core.jar文件路径
                File arthasCoreJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                // 获取arthas-spy.jar文件路径（位于core.jar同级目录）
                File spyJarFile = new File(arthasCoreJarFile.getParentFile(), ARTHAS_SPY_JAR);
                // 将spyJar添加到BootstrapClassLoader的搜索路径
                // 该操作只能让 BootstrapClassLoader 加载spyJar中的类，但无法影响其他类加载器（如 AppClassLoader、自定义类加载器）。
                // 需要看后面的增强方法com.taobao.arthas.core.server.ArthasBootstrap.enhanceClassLoader
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));
            } else {
                // 无法找到spyJar时抛出异常
                throw new IllegalStateException("can not find " + ARTHAS_SPY_JAR);
            }
        }
    }

    /**
     * 增强ClassLoader，解决类加载器中SpyAPI不可见的问题
     *
     * 应用场景：
     * 1. 自定义类加载器（如Tomcat WebappClassLoader、OSGi BundleClassLoader）
     * 2. 重写了loadClass方法的类加载器（打破标准双亲委派）
     * 3. 类加载器隔离策略严格的框架环境
     *
     * 技术原理：
     * 通过ASM动态修改类加载器的字节码，在loadClass方法中注入SpyAPI加载逻辑
     * 字节码增强逻辑详见仓库（https://github.com/alibaba/bytekit）
     */
    void enhanceClassLoader() throws IOException, UnmodifiableClassException {
        // 1. 配置检查：若未指定需要增强的类加载器，直接返回
        if (configure.getEnhanceLoaders() == null) {
            return;
        }

        // 2. 解析配置的类加载器名称，去重后存入集合
        Set<String> loaders = new HashSet<>();
        for (String s : configure.getEnhanceLoaders().split(",")) {
            loaders.add(s.trim()); // 支持配置多个类加载器，如"java.lang.ClassLoader,org.apache.catalina.loader.WebappClassLoader"
        }

        // 3. 加载ClassLoader增强工具类的字节码
        //    ClassLoader_Instrument类包含对ClassLoader.loadClass方法的增强逻辑
        String resourcePath = ClassLoader_Instrument.class.getName().replace('.', '/') + ".class";
        byte[] classBytes = IOUtils.getBytes(
                ArthasBootstrap.class.getClassLoader().getResourceAsStream(resourcePath)
        );

        // 4. 创建类匹配器：用于指定需要增强的类加载器
        //    SimpleClassMatcher支持按类名精确匹配或正则匹配
        SimpleClassMatcher matcher = new SimpleClassMatcher(loaders);

        // 5. 创建仪器配置：指定增强的字节码和匹配规则
        //    AsmUtils.toClassNode将字节码转换为ASM的ClassNode对象，便于解析和修改
        InstrumentConfig instrumentConfig = new InstrumentConfig(
                AsmUtils.toClassNode(classBytes),
                matcher
        );

        // 6. 创建仪器解析结果并添加配置
        InstrumentParseResult instrumentParseResult = new InstrumentParseResult();
        instrumentParseResult.addInstrumentConfig(instrumentConfig);

        // 7. 创建字节码转换器：负责将增强逻辑应用到目标类加载器
        classLoaderInstrumentTransformer = new InstrumentTransformer(instrumentParseResult);

        // 8. 注册转换器到Instrumentation
        //    参数true表示：对已加载的类也进行重新转换（热更新）
        instrumentation.addTransformer(classLoaderInstrumentTransformer, true);

        // 9. 触发类加载器字节码重转换
        if (loaders.size() == 1 && loaders.contains(ClassLoader.class.getName())) {
            // 特殊情况：仅增强基础ClassLoader类
            instrumentation.retransformClasses(ClassLoader.class);
        } else {
            // 通用情况：按配置的类加载器列表批量转换
            InstrumentationUtils.trigerRetransformClasses(instrumentation, loaders);
        }
    }

    /**
     * 初始化Arthas环境配置，加载配置属性
     * @param argsMap 启动参数映射
     * @throws IOException 读取配置文件时的IO异常
     */
    private void initArthasEnvironment(Map<String, String> argsMap) throws IOException {
        // 初始化Arthas环境（若未初始化）
        if (arthasEnvironment == null) {
            arthasEnvironment = new ArthasEnvironment();
        }

        /**
         * 配置优先级：
         * 1. 脚本传过来的配置项（命令行参数）
         * 2. 系统环境变量
         * 3. 系统属性
         * 4. arthas.properties配置文件
         * 可通过arthas.config.overrideAll=true反转优先级
         */
        Map<String, Object> copyMap;
        if (argsMap != null) {
            // 复制参数映射，并添加arthas.home配置
            copyMap = new HashMap<String, Object>(argsMap);
            if (!copyMap.containsKey(ARTHAS_HOME_PROPERTY)) {
                copyMap.put(ARTHAS_HOME_PROPERTY, arthasHome());
            }
        } else {
            // 无参数时创建默认映射
            copyMap = new HashMap<String, Object>(1);
            copyMap.put(ARTHAS_HOME_PROPERTY, arthasHome());
        }

        // 添加命令行参数作为最高优先级的属性源
        MapPropertySource mapPropertySource = new MapPropertySource("args", copyMap);
        arthasEnvironment.addFirst(mapPropertySource);

        // 尝试加载arthas.properties配置文件
        tryToLoadArthasProperties();

        // 绑定配置到环境，生成Configure对象
        configure = new Configure();
        BinderUtils.inject(arthasEnvironment, configure);
    }

    /**
     * 获取Arthas安装目录
     * @return Arthas安装目录路径
     */
    private static String arthasHome() {
        // 若已缓存安装目录，直接返回
        if (ARTHAS_HOME != null) {
            return ARTHAS_HOME;
        }
        // 从代码源获取安装目录
        CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            try {
                ARTHAS_HOME = new File(codeSource.getLocation().toURI().getSchemeSpecificPart()).getParentFile().getAbsolutePath();
            } catch (Throwable e) {
                AnsiLog.error("try to find arthas.home from CodeSource error", e);
            }
        }
        // 若获取失败，使用当前目录
        if (ARTHAS_HOME == null) {
            ARTHAS_HOME = new File("").getAbsolutePath();
        }
        return ARTHAS_HOME;
    }

    /**
     * 解析环境属性，支持占位符
     * @param arthasEnvironment Arthas环境
     * @param key 属性键
     * @param defaultValue 默认值
     * @return 解析后的属性值
     */
    static String reslove(ArthasEnvironment arthasEnvironment, String key, String defaultValue) {
        String value = arthasEnvironment.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return arthasEnvironment.resolvePlaceholders(value);
    }

    /**
     * 尝试加载arthas.properties配置文件
     * @throws IOException 读取配置文件时的IO异常
     */
    private void tryToLoadArthasProperties() throws IOException {
        // 解析配置位置属性
        this.arthasEnvironment.resolvePlaceholders(CONFIG_LOCATION_PROPERTY);

        String location = reslove(arthasEnvironment, CONFIG_LOCATION_PROPERTY, null);

        if (location == null) {
            // 若未指定位置，使用Arthas安装目录
            location = arthasHome();
        }

        String configName = reslove(arthasEnvironment, CONFIG_NAME_PROPERTY, "arthas");

        if (location != null) {
            // 构建配置文件路径
            if (!location.endsWith(".properties")) {
                location = new File(location, configName + ".properties").getAbsolutePath();
            }
            // 若配置文件存在，则加载配置
            if (new File(location).exists()) {
                Properties properties = FileUtils.readProperties(location);

                boolean overrideAll = false;
                // 检查是否覆盖所有属性
                if (arthasEnvironment.containsProperty(CONFIG_OVERRIDE_ALL)) {
                    overrideAll = arthasEnvironment.getRequiredProperty(CONFIG_OVERRIDE_ALL, boolean.class);
                } else {
                    overrideAll = Boolean.parseBoolean(properties.getProperty(CONFIG_OVERRIDE_ALL, "false"));
                }

                // 添加配置属性源，根据overrideAll决定优先级
                PropertySource<?> propertySource = new PropertiesPropertySource(location, properties);
                if (overrideAll) {
                    arthasEnvironment.addFirst(propertySource);
                } else {
                    arthasEnvironment.addLast(propertySource);
                }
            }
        }
    }

    /**
     * 绑定Arthas服务器，启动通信服务（Telnet/HTTP）
     * 该方法是Arthas服务端启动的核心流程，包含配置检查、端口初始化、服务注册等关键步骤
     *
     * @param configure 配置信息，包含端口、认证信息等启动参数
     * @throws Throwable 启动过程中可能抛出的异常（如端口占用、初始化失败等）
     */
    private void bind(Configure configure) throws Throwable {
        // 记录服务启动时间，用于统计启动耗时
        long start = System.currentTimeMillis();

        // 原子性检查绑定状态，确保单例启动（CAS操作避免并发问题）
        // isBindRef为AtomicBoolean类型，初始为false
        if (!isBindRef.compareAndSet(false, true)) {
            throw new IllegalStateException("already bind"); // 已绑定则抛出异常
        }

        // 自动分配随机Telnet端口（配置为0时）
        if (configure.getTelnetPort() != null && configure.getTelnetPort() == 0) {
            int newTelnetPort = SocketUtils.findAvailableTcpPort(); // 查找可用TCP端口
            configure.setTelnetPort(newTelnetPort); // 设置分配的端口
            logger().info("generate random telnet port: " + newTelnetPort); // 日志记录随机端口
        }
        // 自动分配随机HTTP端口（配置为0时）
        if (configure.getHttpPort() != null && configure.getHttpPort() == 0) {
            int newHttpPort = SocketUtils.findAvailableTcpPort(); // 查找可用TCP端口
            configure.setHttpPort(newHttpPort); // 设置分配的端口
            logger().info("generate random http port: " + newHttpPort); // 日志记录随机端口
        }
        // 自动获取应用名称（优先取项目名，其次取Spring应用名）
        if (configure.getAppName() == null) {
            configure.setAppName(System.getProperty(ArthasConstants.PROJECT_NAME,
                    System.getProperty(ArthasConstants.SPRING_APPLICATION_NAME, null)));
        }

        // 启动隧道客户端（用于连接远程隧道服务器）
        try {
            if (configure.getTunnelServer() != null) {
                tunnelClient = new TunnelClient(); // 初始化隧道客户端
                tunnelClient.setAppName(configure.getAppName()); // 设置应用名称
                tunnelClient.setId(configure.getAgentId()); // 设置代理ID
                tunnelClient.setTunnelServerUrl(configure.getTunnelServer()); // 设置隧道服务器地址
                tunnelClient.setVersion(ArthasBanner.version()); // 设置Arthas版本
                // 启动隧道客户端并等待连接（超时10秒）
                ChannelFuture channelFuture = tunnelClient.start();
                channelFuture.await(10, TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            logger().error("start tunnel client error", t); // 记录隧道启动异常
        }

        try {
            // 配置Shell服务器选项
            ShellServerOptions options = new ShellServerOptions()
                    .setInstrumentation(instrumentation) // 设置Java Instrumentation实例
                    .setPid(PidUtils.currentLongPid()) // 设置当前进程PID
                    .setWelcomeMessage(ArthasBanner.welcome()); // 设置欢迎消息
            if (configure.getSessionTimeout() != null) {
                // 转换会话超时时间为毫秒（配置单位为分钟）
                options.setSessionTimeout(configure.getSessionTimeout() * 1000);
            }

            // 初始化HTTP会话管理器（管理客户端会话状态）
            this.httpSessionManager = new HttpSessionManager();
            // 安全检查：当监听所有IP且未设置密码时强制生成随机密码
            if (IPUtils.isAllZeroIP(configure.getIp()) && StringUtils.isBlank(configure.getPassword())) {
                String errorMsg = "Listening on 0.0.0.0 is very dangerous! External users can connect to your machine! "
                        + "No password is currently configured. " + "Therefore, a default password is generated, "
                        + "and clients need to use the password to connect!";
                AnsiLog.error(errorMsg); // 输出错误级日志
                configure.setPassword(StringUtils.randomString(64)); // 生成64位随机密码
                AnsiLog.error("Generated arthas password: " + configure.getPassword()); // 输出生成的密码
                logger().error(errorMsg); // 记录错误日志
                logger().info("Generated arthas password: " + configure.getPassword()); // 记录密码信息
            }

            // 初始化安全认证器（处理客户端认证）
            this.securityAuthenticator = new SecurityAuthenticatorImpl(configure.getUsername(), configure.getPassword());

            // 创建Shell服务器实例（核心服务端组件）
            shellServer = new ShellServerImpl(options);

            // 处理禁用命令配置（从配置中解析禁用的命令列表）
            List<String> disabledCommands = new ArrayList<String>();
            if (configure.getDisabledCommands() != null) {
                String[] strings = StringUtils.tokenizeToStringArray(configure.getDisabledCommands(), ",");
                if (strings != null) {
                    disabledCommands.addAll(Arrays.asList(strings));
                }
            }
            // 注册内置命令包（包含Arthas所有内置命令）
            BuiltinCommandPack builtinCommands = new BuiltinCommandPack(disabledCommands);
            List<CommandResolver> resolvers = new ArrayList<CommandResolver>();
            resolvers.add(builtinCommands);

            // 初始化Netty工作线程组（处理网络IO事件）
            workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("arthas-TermServer", true));

            // 注册Telnet服务端（若配置了有效端口）
            if (configure.getTelnetPort() != null && configure.getTelnetPort() > 0) {
                logger().info("try to bind telnet server, host: {}, port: {}.", configure.getIp(), configure.getTelnetPort());
                // 注册Telnet服务器（使用HTTP兼容的Telnet实现）
                shellServer.registerTermServer(new HttpTelnetTermServer(configure.getIp(), configure.getTelnetPort(),
                        options.getConnectionTimeout(), workerGroup, httpSessionManager));
            } else {
                logger().info("telnet port is {}, skip bind telnet server.", configure.getTelnetPort());
            }
            // 注册HTTP服务端（若配置了有效端口）
            if (configure.getHttpPort() != null && configure.getHttpPort() > 0) {
                logger().info("try to bind http server, host: {}, port: {}.", configure.getIp(), configure.getHttpPort());
                // 注册HTTP服务器（处理Web客户端连接）
                shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                        options.getConnectionTimeout(), workerGroup, httpSessionManager));
            } else {
                // 隧道模式下即使未配置HTTP端口也注册服务端（兼容隧道通信）
                if (configure.getTunnelServer() != null) {
                    shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                            options.getConnectionTimeout(), workerGroup, httpSessionManager));
                }
                logger().info("http port is {}, skip bind http server.", configure.getHttpPort());
            }

            // 注册所有命令解析器（使命令可被解析执行）
            for (CommandResolver resolver : resolvers) {
                shellServer.registerCommandResolver(resolver);
            }

            // 启动服务端监听（BindHandler处理绑定结果）
            shellServer.listen(new BindHandler(isBindRef));
            if (!isBind()) {
                // 绑定失败时抛出异常（端口可能被占用）
                throw new IllegalStateException("Arthas failed to bind telnet or http port! Telnet port: "
                        + String.valueOf(configure.getTelnetPort()) + ", http port: "
                        + String.valueOf(configure.getHttpPort()));
            }

            // 初始化会话管理器（管理客户端会话生命周期）
            sessionManager = new SessionManagerImpl(options, shellServer.getCommandManager(), shellServer.getJobController());
            // 初始化HTTP API处理器（处理HTTP接口请求）
            httpApiHandler = new HttpApiHandler(historyManager, sessionManager);

            // 记录服务启动信息（监听地址、端口、超时时间）
            logger().info("as-server listening on network={};telnet={};http={};timeout={};", configure.getIp(),
                    configure.getTelnetPort(), configure.getHttpPort(), options.getConnectionTimeout());

            // 异步上报启动统计信息（用于Arthas使用情况分析）
            if (configure.getStatUrl() != null) {
                logger().info("arthas stat url: {}", configure.getStatUrl());
            }
            UserStatUtil.setStatUrl(configure.getStatUrl()); // 设置统计URL
            UserStatUtil.setAgentId(configure.getAgentId()); // 设置代理ID
            UserStatUtil.arthasStart(); // 上报启动事件

            // 初始化SpyAPI（Arthas核心监控接口）
            try {
                SpyAPI.init(); // 标记SpyAPI已初始化
            } catch (Throwable e) {
                // 初始化失败时忽略异常（可能已被其他方式初始化）
            }

            // 记录启动耗时（从start到当前的时间差）
            logger().info("as-server started in {} ms", System.currentTimeMillis() - start);
        } catch (Throwable e) {
            // 启动过程中出错时记录错误并销毁资源
            logger().error("Error during start as-server", e);
            destroy(); // 调用销毁方法释放资源
            throw e; // 重新抛出异常
        }
    }

    /**
     * 关闭工作线程组
     */
    private void shutdownWorkGroup() {
        if (workerGroup != null) {
            // 优雅关闭工作线程组，等待200ms
            workerGroup.shutdownGracefully(200, 200, TimeUnit.MILLISECONDS);
            workerGroup = null;
        }
    }

    /**
     * 判断服务端是否已经启动
     * @return true: 服务端已启动; false: 服务端关闭
     */
    public boolean isBind() {
        return isBindRef.get();
    }

    /**
     * 重置所有增强类
     * @return 增强影响统计
     * @throws UnmodifiableClassException 类不可修改时的异常
     */
    public EnhancerAffect reset() throws UnmodifiableClassException {
        return Enhancer.reset(this.instrumentation, new WildcardMatcher("*"));
    }

    /**
     * 销毁Arthas服务器，释放所有资源
     */
    public void destroy() {
        // 关闭Shell服务端
        if (shellServer != null) {
            shellServer.close();
            shellServer = null;
        }
        // 关闭会话管理器
        if (sessionManager != null) {
            sessionManager.close();
            sessionManager = null;
        }
        // 停止HTTP会话管理器
        if (this.httpSessionManager != null) {
            httpSessionManager.stop();
        }
        // 取消定时器
        if (timer != null) {
            timer.cancel();
        }
        // 停止隧道客户端
        if (this.tunnelClient != null) {
            try {
                tunnelClient.stop();
            } catch (Throwable e) {
                logger().error("stop tunnel client error", e);
            }
        }
        // 关闭定时任务执行器
        if (executorService != null) {
            executorService.shutdownNow();
        }
        // 销毁转换器管理器
        if (transformerManager != null) {
            transformerManager.destroy();
        }
        // 移除类加载器转换器
        if (classLoaderInstrumentTransformer != null) {
            instrumentation.removeTransformer(classLoaderInstrumentTransformer);
        }
        // 清除SpyAPI引用
        cleanUpSpyReference();
        // 关闭工作线程组
        shutdownWorkGroup();
        // 销毁用户统计
        UserStatUtil.destroy();
        // 移除关闭钩子线程
        if (shutdown != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (Throwable t) {
                // 移除失败时忽略异常
            }
        }
        // 记录销毁完成
        logger().info("as-server destroy completed.");
        // 停止日志上下文
        if (loggerContext != null) {
            loggerContext.stop();
        }
    }

    /**
     * 获取ArthasBootstrap单例实例（带字符串参数）
     * @param instrumentation JVM instrumentation接口
     * @param args 启动参数字符串
     * @return ArthasBootstrap实例
     * @throws Throwable 初始化过程中可能抛出的异常
     */
    public synchronized static ArthasBootstrap getInstance(Instrumentation instrumentation, String args) throws Throwable {
        if (arthasBootstrap != null) {
            return arthasBootstrap;
        }

        // 将参数字符串转换为映射，并添加arthas前缀
        Map<String, String> argsMap = FeatureCodec.DEFAULT_COMMANDLINE_CODEC.toMap(args);
        Map<String, String> mapWithPrefix = new HashMap<String, String>(argsMap.size());
        for (Entry<String, String> entry : argsMap.entrySet()) {
            mapWithPrefix.put("arthas." + entry.getKey(), entry.getValue());
        }
        return getInstance(instrumentation, mapWithPrefix);
    }

    /**
     * 单例：双重检查锁定（DCL）的简化版，虽然代码中只有一次null检查，但由于方法整体同步，效果等同于：
     * if (arthasBootstrap == null) {
     *     synchronized (ArthasBootstrap.class) {
     *         if (arthasBootstrap == null) {
     *             arthasBootstrap = new ArthasBootstrap(...);
     *         }
     *     }
     * }
     *
     * 获取ArthasBootstrap单例实例（带映射参数）
     * @param instrumentation JVM instrumentation接口
     * @param args 启动参数映射
     * @return ArthasBootstrap实例
     * @throws Throwable 初始化过程中可能抛出的异常
     */
    public synchronized static ArthasBootstrap getInstance(Instrumentation instrumentation, Map<String, String> args) throws Throwable {
        if (arthasBootstrap == null) {
            arthasBootstrap = new ArthasBootstrap(instrumentation, args);
        }
        return arthasBootstrap;
    }

    /**
     * 获取ArthasBootstrap单例实例（已初始化时）
     * @return ArthasBootstrap实例
     */
    public static ArthasBootstrap getInstance() {
        if (arthasBootstrap == null) {
            throw new IllegalStateException("ArthasBootstrap must be initialized before!");
        }
        return arthasBootstrap;
    }

    /**
     * 执行异步命令
     * @param command 待执行的命令
     */
    public void execute(Runnable command) {
        executorService.execute(command);
    }

    /**
     * 清除SpyAPI里的引用，释放资源
     */
    private void cleanUpSpyReference() {
        try {
            // 设置NopSpy并销毁SpyAPI
            SpyAPI.setNopSpy();
            SpyAPI.destroy();
        } catch (Throwable e) {
            // 清除失败时忽略异常
        }
        // 重置Arthas类加载器（通过反射调用AgentBootstrap的方法）
        try {
            Class<?> clazz = ClassLoader.getSystemClassLoader().loadClass("com.taobao.arthas.agent334.AgentBootstrap");
            Method method = clazz.getDeclaredMethod("resetArthasClassLoader");
            method.invoke(null);
        } catch (Throwable e) {
            // 反射调用失败时忽略异常
        }
    }

    // 以下为getter方法，用于获取各组件实例
    public TunnelClient getTunnelClient() {
        return tunnelClient;
    }

    public ShellServer getShellServer() {
        return shellServer;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public Timer getTimer() {
        return this.timer;
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return this.executorService;
    }

    public Instrumentation getInstrumentation() {
        return this.instrumentation;
    }

    public TransformerManager getTransformerManager() {
        return this.transformerManager;
    }

    private Logger logger() {
        return LoggerFactory.getLogger(this.getClass());
    }

    public ResultViewResolver getResultViewResolver() {
        return resultViewResolver;
    }

    public HistoryManager getHistoryManager() {
        return historyManager;
    }

    public HttpApiHandler getHttpApiHandler() {
        return httpApiHandler;
    }

    public File getOutputPath() {
        return outputPath;
    }

    public SecurityAuthenticator getSecurityAuthenticator() {
        return securityAuthenticator;
    }

    public Configure getConfigure() {
        return configure;
    }
}