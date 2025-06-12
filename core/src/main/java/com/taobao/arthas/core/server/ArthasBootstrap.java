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

        // 4. 增强ClassLoader，解决类加载器中SpyAPI不可见的问题
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
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));
            } else {
                // 无法找到spyJar时抛出异常
                throw new IllegalStateException("can not find " + ARTHAS_SPY_JAR);
            }
        }
    }

    /**
     * 增强ClassLoader，解决类加载器中SpyAPI不可见的问题
     * @throws IOException 读取类文件时的IO异常
     * @throws UnmodifiableClassException 类不可修改时的异常
     */
    void enhanceClassLoader() throws IOException, UnmodifiableClassException {
        // 若未配置需要增强的类加载器，则直接返回
        if (configure.getEnhanceLoaders() == null) {
            return;
        }
        // 解析需要增强的类加载器名称，存入集合
        Set<String> loaders = new HashSet<String>();
        for (String s : configure.getEnhanceLoaders().split(",")) {
            loaders.add(s.trim());
        }

        // 读取ClassLoader_Instrument类的字节码（用于增强ClassLoader）
        byte[] classBytes = IOUtils.getBytes(ArthasBootstrap.class.getClassLoader()
                .getResourceAsStream(ClassLoader_Instrument.class.getName().replace('.', '/') + ".class"));

        // 创建类匹配器，匹配需要增强的类加载器
        SimpleClassMatcher matcher = new SimpleClassMatcher(loaders);
        // 创建仪器配置，指定字节码和匹配器
        InstrumentConfig instrumentConfig = new InstrumentConfig(AsmUtils.toClassNode(classBytes), matcher);

        // 创建仪器解析结果，添加配置
        InstrumentParseResult instrumentParseResult = new InstrumentParseResult();
        instrumentParseResult.addInstrumentConfig(instrumentConfig);
        // 创建类加载器转换器
        classLoaderInstrumentTransformer = new InstrumentTransformer(instrumentParseResult);
        // 添加转换器到instrumentation，true表示重新转换已加载的类
        instrumentation.addTransformer(classLoaderInstrumentTransformer, true);

        // 触发类重新转换
        if (loaders.size() == 1 && loaders.contains(ClassLoader.class.getName())) {
            // 若只增强ClassLoader类，直接重新转换ClassLoader
            instrumentation.retransformClasses(ClassLoader.class);
        } else {
            // 否则按配置的类加载器列表触发重新转换
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
     * 绑定Arthas服务器，启动通信服务
     * @param configure 配置信息
     * @throws Throwable 启动过程中可能抛出的异常
     */
    private void bind(Configure configure) throws Throwable {
        // 记录启动时间
        long start = System.currentTimeMillis();

        // 检查并设置绑定状态，保证单例启动
        if (!isBindRef.compareAndSet(false, true)) {
            throw new IllegalStateException("already bind");
        }

        // 初始化随机端口（若配置为0则自动分配）
        if (configure.getTelnetPort() != null && configure.getTelnetPort() == 0) {
            int newTelnetPort = SocketUtils.findAvailableTcpPort();
            configure.setTelnetPort(newTelnetPort);
            logger().info("generate random telnet port: " + newTelnetPort);
        }
        if (configure.getHttpPort() != null && configure.getHttpPort() == 0) {
            int newHttpPort = SocketUtils.findAvailableTcpPort();
            configure.setHttpPort(newHttpPort);
            logger().info("generate random http port: " + newHttpPort);
        }
        // 尝试获取应用名称
        if (configure.getAppName() == null) {
            configure.setAppName(System.getProperty(ArthasConstants.PROJECT_NAME,
                    System.getProperty(ArthasConstants.SPRING_APPLICATION_NAME, null)));
        }

        // 启动隧道客户端（若配置了隧道服务器）
        try {
            if (configure.getTunnelServer() != null) {
                tunnelClient = new TunnelClient();
                tunnelClient.setAppName(configure.getAppName());
                tunnelClient.setId(configure.getAgentId());
                tunnelClient.setTunnelServerUrl(configure.getTunnelServer());
                tunnelClient.setVersion(ArthasBanner.version());
                // 启动隧道客户端并等待连接
                ChannelFuture channelFuture = tunnelClient.start();
                channelFuture.await(10, TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            logger().error("start tunnel client error", t);
        }

        try {
            // 配置Shell服务器选项
            ShellServerOptions options = new ShellServerOptions()
                    .setInstrumentation(instrumentation)
                    .setPid(PidUtils.currentLongPid())
                    .setWelcomeMessage(ArthasBanner.welcome());
            if (configure.getSessionTimeout() != null) {
                // 设置会话超时时间（毫秒）
                options.setSessionTimeout(configure.getSessionTimeout() * 1000);
            }

            // 初始化HTTP会话管理器
            this.httpSessionManager = new HttpSessionManager();
            // 安全检查：当监听0.0.0.0且未设置密码时，强制生成密码
            if (IPUtils.isAllZeroIP(configure.getIp()) && StringUtils.isBlank(configure.getPassword())) {
                String errorMsg = "Listening on 0.0.0.0 is very dangerous! External users can connect to your machine! "
                        + "No password is currently configured. " + "Therefore, a default password is generated, "
                        + "and clients need to use the password to connect!";
                AnsiLog.error(errorMsg);
                // 生成随机密码
                configure.setPassword(StringUtils.randomString(64));
                AnsiLog.error("Generated arthas password: " + configure.getPassword());

                logger().error(errorMsg);
                logger().info("Generated arthas password: " + configure.getPassword());
            }

            // 初始化安全认证器
            this.securityAuthenticator = new SecurityAuthenticatorImpl(configure.getUsername(), configure.getPassword());

            // 创建Shell服务器实例
            shellServer = new ShellServerImpl(options);

            // 处理禁用命令配置
            List<String> disabledCommands = new ArrayList<String>();
            if (configure.getDisabledCommands() != null) {
                String[] strings = StringUtils.tokenizeToStringArray(configure.getDisabledCommands(), ",");
                if (strings != null) {
                    disabledCommands.addAll(Arrays.asList(strings));
                }
            }
            // 注册内置命令包
            BuiltinCommandPack builtinCommands = new BuiltinCommandPack(disabledCommands);
            List<CommandResolver> resolvers = new ArrayList<CommandResolver>();
            resolvers.add(builtinCommands);

            // 初始化Netty工作线程组
            workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("arthas-TermServer", true));

            // 注册Telnet服务端（若配置了端口）
            if (configure.getTelnetPort() != null && configure.getTelnetPort() > 0) {
                logger().info("try to bind telnet server, host: {}, port: {}.", configure.getIp(), configure.getTelnetPort());
                shellServer.registerTermServer(new HttpTelnetTermServer(configure.getIp(), configure.getTelnetPort(),
                        options.getConnectionTimeout(), workerGroup, httpSessionManager));
            } else {
                logger().info("telnet port is {}, skip bind telnet server.", configure.getTelnetPort());
            }
            // 注册HTTP服务端（若配置了端口）
            if (configure.getHttpPort() != null && configure.getHttpPort() > 0) {
                logger().info("try to bind http server, host: {}, port: {}.", configure.getIp(), configure.getHttpPort());
                shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                        options.getConnectionTimeout(), workerGroup, httpSessionManager));
            } else {
                // 隧道模式下即使未配置HTTP端口也注册服务端
                if (configure.getTunnelServer() != null) {
                    shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                            options.getConnectionTimeout(), workerGroup, httpSessionManager));
                }
                logger().info("http port is {}, skip bind http server.", configure.getHttpPort());
            }

            // 注册所有命令解析器
            for (CommandResolver resolver : resolvers) {
                shellServer.registerCommandResolver(resolver);
            }

            // 启动服务端监听
            shellServer.listen(new BindHandler(isBindRef));
            if (!isBind()) {
                // 绑定失败时抛出异常
                throw new IllegalStateException("Arthas failed to bind telnet or http port! Telnet port: "
                        + String.valueOf(configure.getTelnetPort()) + ", http port: "
                        + String.valueOf(configure.getHttpPort()));
            }

            // 初始化会话管理器和HTTP API处理器
            sessionManager = new SessionManagerImpl(options, shellServer.getCommandManager(), shellServer.getJobController());
            httpApiHandler = new HttpApiHandler(historyManager, sessionManager);

            // 记录服务启动信息
            logger().info("as-server listening on network={};telnet={};http={};timeout={};", configure.getIp(),
                    configure.getTelnetPort(), configure.getHttpPort(), options.getConnectionTimeout());

            // 异步上报启动统计信息
            if (configure.getStatUrl() != null) {
                logger().info("arthas stat url: {}", configure.getStatUrl());
            }
            UserStatUtil.setStatUrl(configure.getStatUrl());
            UserStatUtil.setAgentId(configure.getAgentId());
            UserStatUtil.arthasStart();

            // 初始化SpyAPI
            try {
                SpyAPI.init();
            } catch (Throwable e) {
                // 初始化失败时忽略异常
            }

            // 记录启动耗时
            logger().info("as-server started in {} ms", System.currentTimeMillis() - start);
        } catch (Throwable e) {
            // 启动过程中出错时记录错误并销毁资源
            logger().error("Error during start as-server", e);
            destroy();
            throw e;
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