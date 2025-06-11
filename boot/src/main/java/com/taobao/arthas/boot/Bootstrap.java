package com.taobao.arthas.boot;

// 导入 ProcessUtils 类中的状态码常量，用于后续错误处理
import static com.taobao.arthas.boot.ProcessUtils.STATUS_EXEC_ERROR;
import static com.taobao.arthas.boot.ProcessUtils.STATUS_EXEC_TIMEOUT;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.common.SocketUtils;
import com.taobao.arthas.common.UsageRender;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.UsageMessageFormatter;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.CLIConfigurator;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 * 该类是 Arthas 的启动引导类，负责解析命令行参数，选择目标进程，
 * 查找或下载 Arthas 相关文件，启动 Arthas 核心程序，并根据需要连接到目标进程。
 * 类名Bootstrap通常在编程里代表引导程序，其功能是启动和初始化应用程序。这是一个约定俗成的命名，看到这个名字，开发者就会明白它是用来引导应用程序启动的。
 * Bootstrap类包含main方法，这是 Java 应用程序的入口点。当运行 Java 程序时，JVM 会从main方法开始执行。
 * Bootstrap类对命令行参数进行解析，并且初始化应用程序所需的配置。它定义了大量的选项和参数，例如telnet-port、http-port、use-version等，这些选项能够让用户灵活地配置应用程序的启动行为。
 * @author hengyunabc 2018-10-26
 */
@Name("arthas-boot")
@Summary("Bootstrap Arthas")
@Description("NOTE: Arthas 4 supports JDK 8+. If you need to diagnose applications running on JDK 6/7, you can use Arthas 3.\n\n"
        +"EXAMPLES:\n" + "  java -jar arthas-boot.jar <pid>\n"
        + "  java -jar arthas-boot.jar --telnet-port 9999 --http-port -1\n"
        + "  java -jar arthas-boot.jar --username admin --password <password>\n"
        + "  java -jar arthas-boot.jar --tunnel-server 'ws://192.168.10.11:7777/ws' --app-name demoapp\n"
        + "  java -jar arthas-boot.jar --tunnel-server 'ws://192.168.10.11:7777/ws' --agent-id bvDOe8XbTM2pQWjF4cfw\n"
        + "  java -jar arthas-boot.jar --stat-url 'http://192.168.10.11:8080/api/stat'\n"
        + "  java -jar arthas-boot.jar -c 'sysprop; thread' <pid>\n"
        + "  java -jar arthas-boot.jar -f batch.as <pid>\n"
        + "  java -jar arthas-boot.jar --use-version 4.0.5\n"
        + "  java -jar arthas-boot.jar --versions\n"
        + "  java -jar arthas-boot.jar --select math-game\n"
        + "  java -jar arthas-boot.jar --session-timeout 3600\n" + "  java -jar arthas-boot.jar --attach-only\n"
        + "  java -jar arthas-boot.jar --disabled-commands stop,dump\n"
        + "  java -jar arthas-boot.jar --repo-mirror aliyun --use-http\n" + "WIKI:\n"
        + "  https://arthas.aliyun.com/doc\n")
public class Bootstrap {
    // 默认的 Telnet 端口号
    private static final int DEFAULT_TELNET_PORT = 3658;
    // 默认的 HTTP 端口号
    private static final int DEFAULT_HTTP_PORT = 8563;
    // 默认的目标 IP 地址
    private static final String DEFAULT_TARGET_IP = "127.0.0.1";
    // Arthas 库文件所在的目录
    private static File ARTHAS_LIB_DIR;

    // 是否显示帮助信息的标志
    private boolean help = false;
    // 目标进程的 PID
    private long pid = -1;
    // 目标 JVM 监听的 IP 地址
    private String targetIp;
    // 目标 JVM 监听的 Telnet 端口号
    private Integer telnetPort;
    // 目标 JVM 监听的 HTTP 端口号
    private Integer httpPort;
    // 会话超时时间（秒）
    private Long sessionTimeout;
    // arthas-client 终端的高度
    private Integer height = null;
    // arthas-client 终端的宽度
    private Integer width = null;
    // 是否显示详细调试信息的标志
    private boolean verbose = false;
    // Arthas 的主目录
    private String arthasHome;
    // 指定使用的 Arthas 版本
    private String useVersion;
    // 是否列出本地和远程 Arthas 版本的标志
    private boolean versions;
    // 远程仓库镜像的名称或 URL
    private String repoMirror;
    // 是否强制使用 HTTP 下载的标志
    private boolean useHttp = false;
    // 是否仅附加到目标进程而不连接的标志
    private boolean attachOnly = false;
    // 要执行的命令
    private String command;
    // 要执行的批处理文件
    private String batchFile;
    // 隧道服务器的 URL
    private String tunnelServer;
    // 注册到隧道服务器的代理 ID
    private String agentId;
    // 应用程序的名称
    private String appName;
    // 用户名
    private String username;
    // 密码
    private String password;
    // 报告统计信息的 URL
    private String statUrl;
    // 通过类名或 JAR 文件名选择目标进程
    private String select;
    // 要禁用的命令列表
    private String disabledCommands;

    // 静态代码块，用于初始化 ARTHAS_LIB_DIR 目录
    static {
        // 尝试从环境变量（操作系统级别的）中获取 ARTHAS_LIB_DIR 的值
        String arthasLibDirEnv = System.getenv("ARTHAS_LIB_DIR");
        if (arthasLibDirEnv != null) {
            // 如果环境变量存在，则使用该值创建文件对象
            ARTHAS_LIB_DIR = new File(arthasLibDirEnv);
            AnsiLog.info("ARTHAS_LIB_DIR: " + arthasLibDirEnv);
        } else {
            // 如果环境变量不存在，则使用默认路径（ ~/.arthas/lib）创建文件对象
            ARTHAS_LIB_DIR = new File(
                    System.getProperty("user.home") + File.separator + ".arthas" + File.separator + "lib");
        }

        try {
            // 尝试创建 ARTHAS_LIB_DIR 目录
            ARTHAS_LIB_DIR.mkdirs();
        } catch (Throwable t) {
            // 忽略创建目录时可能出现的异常
        }
        if (!ARTHAS_LIB_DIR.exists()) {
            // 如果目录仍然不存在，则尝试使用临时目录
            ARTHAS_LIB_DIR = new File(System.getProperty("java.io.tmpdir") + File.separator + ".arthas" + File.separator + "lib");
            try {
                // 再次尝试创建目录
                ARTHAS_LIB_DIR.mkdirs();
            } catch (Throwable e) {
                // 忽略创建目录时可能出现的异常
            }
        }
        if (!ARTHAS_LIB_DIR.exists()) {
            // 如果最终目录仍然不存在，则输出错误信息
            System.err.println("Can not find directory to save arthas lib. please try to set user home by -Duser.home=");
        }
    }

    // 设置目标进程的 PID
    // @Argument 出自这个仓库https://github.com/alibaba/cli
    // pid是命令行的第一个参数（索引为 0），且不是必需参数。
    // 解析器会扫描Bootstrap类中的所有方法和字段，找到使用了@Argument注解的setPid方法。当执行命令行时，如果传入了pid参数，解析器会调用setPid方法将参数值赋给pid字段。
    @Argument(argName = "pid", index = 0, required = false)
    @Description("Target pid")
    public void setPid(long pid) {
        this.pid = pid;
    }

    // 设置是否显示帮助信息
    // 当用户在命令行中使用 -h 或者 --help 选项时，该方法会被调用，并且 help 参数会被设置为 true。
    @Option(shortName = "h", longName = "help", flag = true)
    @Description("Print usage")
    public void setHelp(boolean help) {
        this.help = help;
    }

    // 设置目标 JVM 监听的 IP 地址
    @Option(longName = "target-ip")
    @Description("The target jvm listen ip, default 127.0.0.1")
    public void setTargetIp(String targetIp) {
        this.targetIp = targetIp;
    }

    // 在启动 Arthas 时，可以使用 --telnet-port 和 --http-port 参数指定端口号（java -jar arthas-boot.jar --telnet-port 9998 --http-port 9999）
    // 用户可以在命令行中实时输入和执行命令，查看命令的执行结果。例如，开发人员在调试代码时，可以通过 Telnet 连接到 Arthas 服务，随时执行各种诊断命令。
    // 设置目标 JVM 监听的 Telnet 端口号
    @Option(longName = "telnet-port")
    @Description("The target jvm listen telnet port, default 3658")
    public void setTelnetPort(int telnetPort) {
        this.telnetPort = telnetPort;
    }

    // 设置目标 JVM 监听的 HTTP 端口号
    @Option(longName = "http-port")
    @Description("The target jvm listen http port, default 8563")
    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    // 设置会话超时时间（秒）
    @Option(longName = "session-timeout")
    @Description("The session timeout seconds, default 1800 (30min)")
    public void setSessionTimeout(Long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    // 设置 Arthas 的主目录
    @Option(longName = "arthas-home")
    @Description("The arthas home")
    public void setArthasHome(String arthasHome) {
        this.arthasHome = arthasHome;
    }

    // 设置指定使用的 Arthas 版本
    @Option(longName = "use-version")
    @Description("Use special version arthas")
    public void setUseVersion(String useVersion) {
        this.useVersion = useVersion;
    }

    // 设置远程仓库镜像的名称或 URL
    @Option(longName = "repo-mirror")
    @Description("Use special remote repository mirror, value is center/aliyun or http repo url.")
    public void setRepoMirror(String repoMirror) {
        this.repoMirror = repoMirror;
    }

    // 设置是否列出本地和远程 Arthas 版本
    @Option(longName = "versions", flag = true)
    @Description("List local and remote arthas versions")
    public void setVersions(boolean versions) {
        this.versions = versions;
    }

    // 设置是否强制使用 HTTP 下载
    @Option(longName = "use-http", flag = true)
    @Description("Enforce use http to download, default use https")
    public void setuseHttp(boolean useHttp) {
        this.useHttp = useHttp;
    }

    // 设置是否仅附加到目标进程而不连接
    @Option(longName = "attach-only", flag = true)
    @Description("Attach target process only, do not connect")
    public void setAttachOnly(boolean attachOnly) {
        this.attachOnly = attachOnly;
    }

    // 设置要执行的命令
    @Option(shortName = "c", longName = "command")
    @Description("Command to execute, multiple commands separated by ;")
    public void setCommand(String command) {
        this.command = command;
    }

    // 设置要执行的批处理文件
    @Option(shortName = "f", longName = "batch-file")
    @Description("The batch file to execute")
    public void setBatchFile(String batchFile) {
        this.batchFile = batchFile;
    }

    // 设置 arthas-client 终端的高度
    @Option(longName = "height")
    @Description("arthas-client terminal height")
    public void setHeight(int height) {
        this.height = height;
    }

    // 设置 arthas-client 终端的宽度
    @Option(longName = "width")
    @Description("arthas-client terminal width")
    public void setWidth(int width) {
        this.width = width;
    }

    // 设置是否显示详细调试信息
    @Option(shortName = "v", longName = "verbose", flag = true)
    @Description("Verbose, print debug info.")
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    // 设置隧道服务器的 URL
    @Option(longName = "tunnel-server")
    @Description("The tunnel server url")
    public void setTunnelServer(String tunnelServer) {
        this.tunnelServer = tunnelServer;
    }

    // 设置注册到隧道服务器的代理 ID
    @Option(longName = "agent-id")
    @Description("The agent id register to tunnel server")
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    // 设置应用程序的名称
    @Option(longName = "app-name")
    @Description("The app name")
    public void setAppName(String appName) {
        this.appName = appName;
    }

    // 设置用户名
    @Option(longName = "username")
    @Description("The username")
    public void setUsername(String username) {
        this.username = username;
    }

    // 设置密码
    @Option(longName = "password")
    @Description("The password")
    public void setPassword(String password) {
        this.password = password;
    }

    // 设置报告统计信息的 URL
    @Option(longName = "stat-url")
    @Description("The report stat url")
    public void setStatUrl(String statUrl) {
        this.statUrl = statUrl;
    }

    // 设置通过类名或 JAR 文件名选择目标进程
    @Option(longName = "select")
    @Description("select target process by classname or JARfilename")
    public void setSelect(String select) {
        this.select = select;
    }

    // 设置要禁用的命令列表
    @Option(longName = "disabled-commands")
    @Description("disable some commands ")
    public void setDisabledCommands(String disabledCommands) {
        this.disabledCommands = disabledCommands;
    }

    /**
     * 程序的入口点，负责解析命令行参数，选择目标进程，
     * 查找或下载 Arthas 相关文件，启动 Arthas 核心程序，并根据需要连接到目标进程。
     *
     * main方法抛出的异常若未被捕获，会导致程序立即退出，JVM 会打印详细的堆栈信息
     *
     * @param args 命令行参数
     * @throws ParserConfigurationException 解析配置文件时可能出现的异常
     * @throws SAXException XML 解析时可能出现的异常
     * @throws IOException 输入输出操作时可能出现的异常
     * @throws ClassNotFoundException 类未找到时可能出现的异常
     * @throws NoSuchMethodException 方法未找到时可能出现的异常
     * @throws SecurityException 安全异常
     * @throws IllegalAccessException 非法访问异常
     * @throws IllegalArgumentException 非法参数异常
     * @throws InvocationTargetException 调用目标异常
     */
    public static void main(String[] args) throws ParserConfigurationException, SAXException, IOException,
            ClassNotFoundException, NoSuchMethodException, SecurityException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException {
        // 获取 JAVA_HOME 环境变量并输出信息，java.home 是 JVM 自动设置的系统属性，指向当前运行的 JRE 目录，无需手动配置，随 JVM 启动自动生成
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            AnsiLog.info("JAVA_HOME: " + javaHome);
        }
        // 获取 arthas-boot 的版本信息并输出
        // 版本信息通常来自 JAR 文件的 META-INF/MANIFEST.MF 文件，或者 Maven/Gradle 生成的 pom.properties 文件
        Package bootstrapPackage = Bootstrap.class.getPackage();
        if (bootstrapPackage != null) {
            String arthasBootVersion = bootstrapPackage.getImplementationVersion();
            if (arthasBootVersion != null) {
                AnsiLog.info("arthas-boot version: " + arthasBootVersion);
            }
        }

        try {
            // 获取 JAVA_TOOL_OPTIONS 环境变量并输出信息
            // 用于获取全局 JVM 参数配置，通过设置这个环境变量，可以在不修改启动命令的情况下，为所有 Java 应用程序提供统一的 JVM 参数。
            // 这在需要对大量 Java 应用进行统一配置的场景下非常有用，例如性能调优、远程调试等。用于获取全局 JVM 参数配置，通过设置这个环境变量，可以在不修改启动命令的情况下，
            // 为所有 Java 应用程序提供统一的 JVM 参数。这在需要对大量 Java 应用进行统一配置的场景下非常有用，例如性能调优、远程调试等。

            // Arthas 启动类的职责是连接已加载的 Agent，而非加载 Agent 本身，而Agent已经加载了JAVA_TOOL_OPTIONS！！
            String javaToolOptions = System.getenv("JAVA_TOOL_OPTIONS");
            if (javaToolOptions != null && !javaToolOptions.trim().isEmpty()) {
                AnsiLog.info("JAVA_TOOL_OPTIONS: " + javaToolOptions);
            }
        } catch (Throwable e) {
            // 忽略获取环境变量时可能出现的异常
        }

        // 创建 Bootstrap 实例
        // 需要初始化的成员变量或资源
        // main方法作为程序入口是静态的，若需调用非静态方法，必须通过实例化对象（不然类似bootstrap.parseCommandLine就不能执行了）
        Bootstrap bootstrap = new Bootstrap();

        // 定义命令行接口
        CLI cli = CLIConfigurator.define(Bootstrap.class);
        // 解析命令行参数
        CommandLine commandLine = cli.parse(Arrays.asList(args));

        try {
            // 将解析后的参数注入到 Bootstrap 实例中
            CLIConfigurator.inject(commandLine, bootstrap);
        } catch (Throwable e) {
            // 打印异常信息
            e.printStackTrace();
            // 输出使用说明
            System.out.println(usage(cli));
            // 退出程序，返回错误码 1
            System.exit(1);
        }

        if (bootstrap.isVerbose()) {
            // 如果设置了详细模式，则将日志级别设置为 ALL
            AnsiLog.level(Level.ALL);
        }
        if (bootstrap.isHelp()) {
            // 如果设置了帮助模式，则输出使用说明并退出程序
            System.out.println(usage(cli));
            System.exit(0);
        }

        if (bootstrap.getRepoMirror() == null || bootstrap.getRepoMirror().trim().isEmpty()) {
            // 如果未设置仓库镜像，则使用默认值
            bootstrap.setRepoMirror("center");
            // 如果时区为 +0800，则使用阿里云镜像
            if (TimeUnit.MILLISECONDS.toHours(TimeZone.getDefault().getOffset(System.currentTimeMillis())) == 8) {
                bootstrap.setRepoMirror("aliyun");
            }
        }
        AnsiLog.debug("Repo mirror:" + bootstrap.getRepoMirror());

        if (bootstrap.isVersions()) {
            // 如果设置了列出版本模式，则输出本地和远程版本信息并退出程序
            System.out.println(UsageRender.render(listVersions()));
            System.exit(0);
        }

        if (JavaVersionUtils.isJava6() || JavaVersionUtils.isJava7()) {
            // 如果使用的是 Java 6 或 7，则强制使用 HTTP 下载
            bootstrap.setuseHttp(true);
            AnsiLog.debug("Java version is {}, only support http, set useHttp to true.",
                    JavaVersionUtils.javaVersionStr());
        }

        // 检查 Telnet 和 HTTP 端口是否被占用
        long telnetPortPid = -1;
        long httpPortPid = -1;
        if (bootstrap.getTelnetPortOrDefault() > 0) {
            // 查找使用 Telnet 端口的进程（异步 + 同步等待：有超时保护，最多等待 5 秒）
            telnetPortPid = SocketUtils.findTcpListenProcess(bootstrap.getTelnetPortOrDefault());
            if (telnetPortPid > 0) {
                // 输出端口被占用的信息
                AnsiLog.info("Process {} already using port {}", telnetPortPid, bootstrap.getTelnetPortOrDefault());
            }
        }
        if (bootstrap.getHttpPortOrDefault() > 0) {
            // 查找使用 HTTP 端口的进程（异步 + 同步等待：有超时保护，最多等待 5 秒）
            httpPortPid = SocketUtils.findTcpListenProcess(bootstrap.getHttpPortOrDefault());
            if (httpPortPid > 0) {
                // 输出端口被占用的信息
                AnsiLog.info("Process {} already using port {}", httpPortPid, bootstrap.getHttpPortOrDefault());
            }
        }

        long pid = bootstrap.getPid();
        // 如果未指定 PID，则选择目标进程
        if (pid < 0) {
            try {
                // 使用 ProcessUtils 类的 select 方法选择目标进程
                pid = ProcessUtils.select(bootstrap.isVerbose(), telnetPortPid, bootstrap.getSelect());
            } catch (InputMismatchException e) {
                // 输入不匹配时输出错误信息并退出程序
                System.out.println("Please input an integer to select pid.");
                System.exit(1);
            }
            if (pid < 0) {
                // 未选择有效 PID 时输出错误信息并退出程序
                System.out.println("Please select an available pid.");
                System.exit(1);
            }
        }

        // 检查 Telnet 端口和目标进程的 PID 是否匹配
        checkTelnetPortPid(bootstrap, telnetPortPid, pid);

        if (httpPortPid > 0 && pid != httpPortPid) {
            // HTTP 端口被占用且目标进程不匹配时输出错误信息并退出程序
            AnsiLog.error("Target process {} is not the process using port {}, you will connect to an unexpected process.",
                    pid, bootstrap.getHttpPortOrDefault());
            AnsiLog.error("1. Try to restart arthas-boot, select process {}, shutdown it first with running the 'stop' command.",
                    httpPortPid);
            AnsiLog.error("2. Or try to use different http port, for example: java -jar arthas-boot.jar --telnet-port 9998 --http-port 9999");
            System.exit(1);
        }

        // 查找 Arthas 主目录
        File arthasHomeDir = null;
        if (bootstrap.getArthasHome() != null) {
            // 验证指定的 Arthas 主目录是否有效
            verifyArthasHome(bootstrap.getArthasHome());
            // 使用指定的 Arthas 主目录
            arthasHomeDir = new File(bootstrap.getArthasHome());
        }
        if (arthasHomeDir == null && bootstrap.getUseVersion() != null) {
            // 如果未指定 Arthas 主目录但指定了版本，则尝试从本地查找
            File specialVersionDir = new File(System.getProperty("user.home"), ".arthas" + File.separator + "lib"
                    + File.separator + bootstrap.getUseVersion() + File.separator + "arthas");
            if (!specialVersionDir.exists()) {
                // 如果本地不存在指定版本，则从远程下载
                DownloadUtils.downArthasPackaging(bootstrap.getRepoMirror(), bootstrap.isUseHttp(),
                        bootstrap.getUseVersion(), ARTHAS_LIB_DIR.getAbsolutePath());
            }
            // 验证下载后的 Arthas 主目录是否有效
            verifyArthasHome(specialVersionDir.getAbsolutePath());
            // 使用下载后的 Arthas 主目录
            arthasHomeDir = specialVersionDir;
        }

        // 尝试将 arthas-boot.jar 所在目录设置为 Arthas 主目录
        if (arthasHomeDir == null) {
            CodeSource codeSource = Bootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                try {
                    // 获取 arthas-boot.jar 的路径
                    File bootJarPath = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                    // 验证路径是否有效
                    verifyArthasHome(bootJarPath.getParent());
                    // 使用 arthas-boot.jar 所在目录作为 Arthas 主目录
                    arthasHomeDir = bootJarPath.getParentFile();
                } catch (Throwable e) {
                    // 忽略异常
                }

            }
        }

        // 尝试从远程服务器下载 Arthas
        if (arthasHomeDir == null) {
            // 检查 ARTHAS_LIB_DIR 目录是否存在，不存在则尝试创建
            boolean checkFile =  ARTHAS_LIB_DIR.exists() || ARTHAS_LIB_DIR.mkdirs();
            if(!checkFile){
                // 创建失败时输出错误信息并退出程序
                AnsiLog.error("cannot create directory {}: maybe permission denied", ARTHAS_LIB_DIR.getAbsolutePath());
                System.exit(1);
            }

            /**
             * 比较本地和远程的最新版本：
             * 1. 获取本地最新版本
             * 2. 获取远程最新版本
             * 3. 比较两个版本
             */
            List<String> versionList = listNames(ARTHAS_LIB_DIR);
            Collections.sort(versionList);

            String localLatestVersion = null;
            if (!versionList.isEmpty()) {
                // 获取本地最新版本
                localLatestVersion = versionList.get(versionList.size() - 1);
            }

            String remoteLatestVersion = DownloadUtils.readLatestReleaseVersion();

            boolean needDownload = false;
            if (localLatestVersion == null) {
                if (remoteLatestVersion == null) {
                    // 本地和远程都未找到版本时输出错误信息并退出程序
                    AnsiLog.error("Can not find Arthas under local: {} and remote repo mirror: {}", ARTHAS_LIB_DIR,
                            bootstrap.getRepoMirror());
                    AnsiLog.error(
                            "Unable to download arthas from remote server, please download the full package according to wiki: https://github.com/alibaba/arthas");
                    System.exit(1);
                } else {
                    // 本地无版本但远程有版本时需要下载
                    needDownload = true;
                }
            } else {
                if (remoteLatestVersion != null) {
                    if (localLatestVersion.compareTo(remoteLatestVersion) < 0) {
                        // 本地版本比远程版本旧时需要下载
                        AnsiLog.info("local latest version: {}, remote latest version: {}, try to download from remote.",
                                localLatestVersion, remoteLatestVersion);
                        needDownload = true;
                    }
                }
            }
            if (needDownload) {
                // 需要下载时从远程服务器下载 Arthas
                DownloadUtils.downArthasPackaging(bootstrap.getRepoMirror(), bootstrap.isUseHttp(),
                        remoteLatestVersion, ARTHAS_LIB_DIR.getAbsolutePath());
                localLatestVersion = remoteLatestVersion;
            }

            // 获取最新版本的 Arthas 主目录
            arthasHomeDir = new File(ARTHAS_LIB_DIR, localLatestVersion + File.separator + "arthas");
        }

        // 验证最终的 Arthas 主目录是否有效
        verifyArthasHome(arthasHomeDir.getAbsolutePath());

        // 输出 Arthas 主目录信息
        AnsiLog.info("arthas home: " + arthasHomeDir);

        if (telnetPortPid > 0 && pid == telnetPortPid) {
            // 如果 Telnet 端口已被目标进程占用，则跳过附加操作
            AnsiLog.info("The target process already listen port {}, skip attach.", bootstrap.getTelnetPortOrDefault());
        } else {
            // 附加操作前再次检查 Telnet 端口和 PID
            telnetPortPid = findProcessByTelnetClient(arthasHomeDir.getAbsolutePath(), bootstrap.getTelnetPortOrDefault());
            checkTelnetPortPid(bootstrap, telnetPortPid, pid);
            if (telnetPortPid > 0 && pid == telnetPortPid) {
                // 如果 Telnet 端口已被目标进程占用，则跳过附加操作
                AnsiLog.info("The target process already listen port {}, skip attach.", bootstrap.getTelnetPortOrDefault());
            } else {

                // 启动 arthas-core.jar
                List<String> attachArgs = new ArrayList<String>();
                attachArgs.add("-jar");
                attachArgs.add(new File(arthasHomeDir, "arthas-core.jar").getAbsolutePath());
                attachArgs.add("-pid");
                attachArgs.add("" + pid);
                if (bootstrap.getTargetIp() != null) {
                    attachArgs.add("-target-ip");
                    attachArgs.add(bootstrap.getTargetIp());
                }

                if (bootstrap.getTelnetPort() != null) {
                    attachArgs.add("-telnet-port");
                    attachArgs.add("" + bootstrap.getTelnetPort());
                }

                if (bootstrap.getHttpPort() != null) {
                    attachArgs.add("-http-port");
                    attachArgs.add("" + bootstrap.getHttpPort());
                }

                attachArgs.add("-core");
                attachArgs.add(new File(arthasHomeDir, "arthas-core.jar").getAbsolutePath());
                attachArgs.add("-agent");
                attachArgs.add(new File(arthasHomeDir, "arthas-agent.jar").getAbsolutePath());
                if (bootstrap.getSessionTimeout() != null) {
                    attachArgs.add("-session-timeout");
                    attachArgs.add("" + bootstrap.getSessionTimeout());
                }

                if (bootstrap.getAppName() != null) {
                    attachArgs.add("-app-name");
                    attachArgs.add(bootstrap.getAppName());
                }

                if (bootstrap.getUsername() != null) {
                    attachArgs.add("-username");
                    attachArgs.add(bootstrap.getUsername());
                }
                if (bootstrap.getPassword() != null) {
                    attachArgs.add("-password");
                    attachArgs.add(bootstrap.getPassword());
                }

                if (bootstrap.getTunnelServer() != null) {
                    attachArgs.add("-tunnel-server");
                    attachArgs.add(bootstrap.getTunnelServer());
                }
                if (bootstrap.getAgentId() != null) {
                    attachArgs.add("-agent-id");
                    attachArgs.add(bootstrap.getAgentId());
                }
                if (bootstrap.getStatUrl() != null) {
                    attachArgs.add("-stat-url");
                    attachArgs.add(bootstrap.getStatUrl());
                }

                if (bootstrap.getDisabledCommands() != null) {
                    attachArgs.add("-disabled-commands");
                    attachArgs.add(bootstrap.getDisabledCommands());
                }

                // 输出尝试附加到目标进程的信息
                AnsiLog.info("Try to attach process " + pid);
                // 输出启动 arthas-core.jar 的参数信息
                AnsiLog.debug("Start arthas-core.jar args: " + attachArgs);
                // 启动 Arthas 核心程序
                ProcessUtils.startArthasCore(pid, attachArgs);

                // 输出附加成功的信息
                AnsiLog.info("Attach process {} success.", pid);
            }
        }

        if (bootstrap.isAttachOnly()) {
            // 如果设置了仅附加模式，则退出程序
            System.exit(0);
        }

        // 启动 Java Telnet 客户端
        // 创建 URLClassLoader 加载 arthas-client.jar
        URLClassLoader classLoader = new URLClassLoader(
                new URL[] { new File(arthasHomeDir, "arthas-client.jar").toURI().toURL() });
        // 加载 TelnetConsole 类
        Class<?> telnetConsoleClas = classLoader.loadClass("com.taobao.arthas.client.TelnetConsole");
        // 获取 TelnetConsole 类的 main 方法
        Method mainMethod = telnetConsoleClas.getMethod("main", String[].class);
        // 创建 Telnet 客户端的参数列表
        List<String> telnetArgs = new ArrayList<String>();

        if (bootstrap.getCommand() != null) {
            telnetArgs.add("-c");
            telnetArgs.add(bootstrap.getCommand());
        }
        if (bootstrap.getBatchFile() != null) {
            telnetArgs.add("-f");
            telnetArgs.add(bootstrap.getBatchFile());
        }
        if (bootstrap.getHeight() != null) {
            telnetArgs.add("--height");
            telnetArgs.add("" + bootstrap.getHeight());
        }
        if (bootstrap.getWidth() != null) {
            telnetArgs.add("--width");
            telnetArgs.add("" + bootstrap.getWidth());
        }

        // 添加 Telnet 端口和 IP 地址
        telnetArgs.add(bootstrap.getTargetIpOrDefault());
        telnetArgs.add("" + bootstrap.getTelnetPortOrDefault());

        // 输出连接信息
        AnsiLog.info("arthas-client connect {} {}", bootstrap.getTargetIpOrDefault(), bootstrap.getTelnetPortOrDefault());
        // 输出启动 arthas-client.jar 的参数信息
        AnsiLog.debug("Start arthas-client.jar args: " + telnetArgs);

        // 修复 https://github.com/alibaba/arthas/issues/833
        Thread.currentThread().setContextClassLoader(classLoader);
        // 调用 TelnetConsole 类的 main 方法启动客户端
        mainMethod.invoke(null, new Object[] { telnetArgs.toArray(new String[0]) });
    }

    /**
     * 检查 Telnet 端口和目标进程的 PID 是否匹配，如果不匹配则输出错误信息并退出程序。
     *
     * @param bootstrap Bootstrap 实例
     * @param telnetPortPid 使用 Telnet 端口的进程 PID
     * @param targetPid 目标进程的 PID
     */
    private static void checkTelnetPortPid(Bootstrap bootstrap, long telnetPortPid, long targetPid) {
        if (telnetPortPid > 0 && targetPid != telnetPortPid) {
            // 输出错误信息
            AnsiLog.error("The telnet port {} is used by process {} instead of target process {}, you will connect to an unexpected process.",
                    bootstrap.getTelnetPortOrDefault(), telnetPortPid, targetPid);
            AnsiLog.error("1. Try to restart arthas-boot, select process {}, shutdown it first with running the 'stop' command.",
                    telnetPortPid);
            AnsiLog.error("2. Or try to stop the existing arthas instance: java -jar arthas-client.jar 127.0.0.1 {} -c \"stop\"", bootstrap.getTelnetPortOrDefault());
            AnsiLog.error("3. Or try to use different telnet port, for example: java -jar arthas-boot.jar --telnet-port 9998 --http-port -1");
            // 退出程序，返回错误码 1
            System.exit(1);
        }
    }

    /**
     * 通过 Telnet 客户端查找使用指定 Telnet 端口的进程 PID。
     *
     * @param arthasHomeDir Arthas 主目录
     * @param telnetPort Telnet 端口号
     * @return 使用指定 Telnet 端口的进程 PID，如果未找到则返回 -1
     */
    private static long findProcessByTelnetClient(String arthasHomeDir, int telnetPort) {
        // 创建 Telnet 客户端的参数列表
        List<String> telnetArgs = new ArrayList<String>();
        telnetArgs.add("-c");
        telnetArgs.add("session");
        telnetArgs.add("--execution-timeout");
        telnetArgs.add("2000");
        // 添加 Telnet 端口和 IP 地址
        telnetArgs.add("127.0.0.1");
        telnetArgs.add("" + telnetPort);

        try {
            // 创建 ByteArrayOutputStream 用于存储输出结果
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            String error = null;
            // 启动 Telnet 客户端并获取执行状态
            int status = ProcessUtils.startArthasClient(arthasHomeDir, telnetArgs, out);
            if (status == STATUS_EXEC_TIMEOUT) {
                // 执行超时处理
                error = "detection timeout";
            } else if (status == STATUS_EXEC_ERROR) {
                // 执行错误处理
                error = "detection error";
                AnsiLog.error("process status: {}", status);
                AnsiLog.error("process output: {}", out.toString());
            } else {
                // 忽略连接错误
            }
            if (error != null) {
                // 输出错误信息
                AnsiLog.error("The telnet port {} is used, but process {}, you will connect to an unexpected process.", telnetPort, error);
                AnsiLog.error("Try to use a different telnet port, for example: java -jar arthas-boot.jar --telnet-port 9998 --http-port -1");
                // 退出程序，返回错误码 1
                System.exit(1);
            }

            // 解析输出结果，查找 Java PID
            String output = out.toString("UTF-8");
            String javaPidLine = null;
            Scanner scanner = new Scanner(output);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains("JAVA_PID")) {
                    javaPidLine = line;
                    break;
                }
            }
            if (javaPidLine != null) {
                // 解析 Java PID
                try {
                    String[] strs = javaPidLine.split("JAVA_PID");
                    if (strs.length > 1) {
                        return Long.parseLong(strs[strs.length - 1].trim());
                    }
                } catch (NumberFormatException e) {
                    // 忽略解析错误
                }
            }
        } catch (Throwable ex) {
            // 输出检测 Telnet 端口时的错误信息
            AnsiLog.error("Detection telnet port error");
            AnsiLog.error(ex);
        }

        return -1;
    }

    /**
     * 列出本地和远程的 Arthas 版本信息。
     *
     * @return 包含本地和远程版本信息的字符串
     */
    private static String listVersions() {
        // 创建 StringBuilder 用于构建版本信息字符串
        StringBuilder result = new StringBuilder(1024);
        // 获取本地版本列表
        List<String> versionList = listNames(ARTHAS_LIB_DIR);
        // 对本地版本列表进行排序
        Collections.sort(versionList);

        result.append("Local versions:\n");
        for (String version : versionList) {
            // 添加本地版本信息
            result.append(" ").append(version).append('\n');
        }
        result.append("Remote versions:\n");

        // 获取远程版本列表
        List<String> remoteVersions = DownloadUtils.readRemoteVersions();
        if (remoteVersions != null) {
            // 反转远程版本列表，使最新版本在前
            Collections.reverse(remoteVersions);
            for (String version : remoteVersions) {
                // 添加远程版本信息
                result.append(" " + version).append('\n');
            }
        } else {
            // 远程版本信息未知时添加提示信息
            result.append(" unknown\n");
        }
        return result.toString();
    }

    /**
     * 获取指定目录下的所有子目录名称。
     *
     * @param dir 目录对象
     * @return 包含子目录名称的列表
     */
    private static List<String> listNames(File dir) {
        // 创建存储子目录名称的列表
        List<String> names = new ArrayList<String>();
        if (!dir.exists()) {
            // 目录不存在时返回空列表
            return names;
        }
        // 获取目录下的所有文件和子目录
        File[] files = dir.listFiles();
        if (files == null) {
            // 文件列表为空时返回空列表
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".") || file.isFile()) {
                // 忽略以 . 开头的文件和普通文件
                continue;
            }
            // 添加子目录名称到列表中
            names.add(name);
        }
        return names;
    }

    /**
     * 验证指定的 Arthas 主目录是否有效，即是否包含必要的 JAR 文件。
     *
     * @param arthasHome Arthas 主目录的路径
     * @throws IllegalArgumentException 如果主目录无效，则抛出该异常
     */
    private static void verifyArthasHome(String arthasHome) {
        // 创建主目录的文件对象
        File home = new File(arthasHome);
        if (home.isDirectory()) {
            // 定义必要的 JAR 文件列表
            String[] fileList = { "arthas-core.jar", "arthas-agent.jar", "arthas-spy.jar" };

            for (String fileName : fileList) {
                if (!new File(home, fileName).exists()) {
                    // 如果缺少必要的 JAR 文件，则抛出异常
                    throw new IllegalArgumentException(
                            fileName + " do not exist, arthas home: " + home.getAbsolutePath());
                }
            }
            return;
        }

        // 如果主目录不是有效的目录，则抛出异常
        throw new IllegalArgumentException("illegal arthas home: " + home.getAbsolutePath());
    }

    /**
     * 生成命令行接口的使用说明。
     *
     * @param cli 命令行接口对象
     * @return 包含使用说明的字符串
     */
    private static String usage(CLI cli) {
        // 创建 StringBuilder 用于构建使用说明字符串
        StringBuilder usageStringBuilder = new StringBuilder();
        // 创建 UsageMessageFormatter 对象
        UsageMessageFormatter usageMessageFormatter = new UsageMessageFormatter();
        // 设置选项比较器为 null
        usageMessageFormatter.setOptionComparator(null);
        // 生成使用说明并添加到 StringBuilder 中
        cli.usage(usageStringBuilder, usageMessageFormatter);
        // 使用 UsageRender 类渲染使用说明字符串
        return UsageRender.render(usageStringBuilder.toString());
    }

    // 获取 Arthas 主目录
    public String getArthasHome() {
        return arthasHome;
    }

    // 获取指定使用的 Arthas 版本
    public String getUseVersion() {
        return useVersion;
    }

    // 获取远程仓库镜像的名称或 URL
    public String getRepoMirror() {
        return repoMirror;
    }

    // 获取是否强制使用 HTTP 下载的标志
    public boolean isUseHttp() {
        return useHttp;
    }

    // 获取目标 JVM 监听的 IP 地址
    public String getTargetIp() {
        return targetIp;
    }

    // 获取目标 JVM 监听的 IP 地址，如果未设置则使用默认值
    public String getTargetIpOrDefault() {
        if (this.targetIp == null) {
            return DEFAULT_TARGET_IP;
        } else {
            return this.targetIp;
        }
    }

    // 获取目标 JVM 监听的 Telnet 端口号
    public Integer getTelnetPort() {
        return telnetPort;
    }

    // 获取目标 JVM 监听的 Telnet 端口号，如果未设置则使用默认值
    public int getTelnetPortOrDefault() {
        if (this.telnetPort == null) {
            return DEFAULT_TELNET_PORT;
        } else {
            return this.telnetPort;
        }
    }

    // 获取目标 JVM 监听的 HTTP 端口号
    public Integer getHttpPort() {
        return httpPort;
    }

    // 获取目标 JVM 监听的 HTTP 端口号，如果未设置则使用默认值
    public int getHttpPortOrDefault() {
        if (this.httpPort == null) {
            return DEFAULT_HTTP_PORT;
        } else {
            return this.httpPort;
        }
    }

    // 获取要执行的命令
    public String getCommand() {
        return command;
    }

    // 获取要执行的批处理文件
    public String getBatchFile() {
        return batchFile;
    }

    // 获取是否仅附加到目标进程而不连接的标志
    public boolean isAttachOnly() {
        return attachOnly;
    }

    // 获取目标进程的 PID
    public long getPid() {
        return pid;
    }

    // 获取是否显示帮助信息的标志
    public boolean isHelp() {
        return help;
    }

    // 获取会话超时时间（秒）
    public Long getSessionTimeout() {
        return sessionTimeout;
    }

    // 获取是否显示详细调试信息的标志
    public boolean isVerbose() {
        return verbose;
    }

    // 获取是否列出本地和远程 Arthas 版本的标志
    public boolean isVersions() {
        return versions;
    }

    // 获取 arthas-client 终端的高度
    public Integer getHeight() {
        return height;
    }

    // 获取 arthas-client 终端的宽度
    public Integer getWidth() {
        return width;
    }

    // 获取隧道服务器的 URL
    public String getTunnelServer() {
        return tunnelServer;
    }

    // 获取注册到隧道服务器的代理 ID
    public String getAgentId() {
        return agentId;
    }

    // 获取应用程序的名称
    public String getAppName() {
        return appName;
    }

    // 获取报告统计信息的 URL
    public String getStatUrl() {
        return statUrl;
    }

    // 获取通过类名或 JAR 文件名选择目标进程的条件
    public String getSelect() {
        return select;
    }

    // 获取用户名
    public String getUsername() {
        return username;
    }

    // 获取密码
    public String getPassword() {
        return password;
    }

    // 获取要禁用的命令列表
    public String getDisabledCommands() {
        return disabledCommands;
    }
}