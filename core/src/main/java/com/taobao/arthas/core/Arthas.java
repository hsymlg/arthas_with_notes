package com.taobao.arthas.core;

// 引入用于将代理加载到目标Java虚拟机的类
import com.sun.tools.attach.VirtualMachine;
// 引入用于描述Java虚拟机的类
import com.sun.tools.attach.VirtualMachineDescriptor;
// 引入用于打印带颜色日志的工具类
import com.taobao.arthas.common.AnsiLog;
// 引入Arthas的常量类
import com.taobao.arthas.common.ArthasConstants;
// 引入用于获取Java版本信息的工具类
import com.taobao.arthas.common.JavaVersionUtils;
// 引入配置类，用于存储和管理Arthas的配置信息
import com.taobao.arthas.core.config.Configure;
// 引入命令行接口相关类，用于解析命令行参数
import com.taobao.middleware.cli.CLI;
// 引入命令行接口工厂类，用于创建命令行接口实例
import com.taobao.middleware.cli.CLIs;
// 引入命令行解析结果类，用于存储解析后的命令行参数
import com.taobao.middleware.cli.CommandLine;
// 引入命令行选项类，用于定义命令行选项
import com.taobao.middleware.cli.Option;
// 引入带类型的命令行选项类，用于定义带类型的命令行选项
import com.taobao.middleware.cli.TypedOption;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Properties;

/**
 * Arthas启动器
 */
public class Arthas {

    // 构造函数，接收命令行参数数组
    // 调用parse方法解析命令行参数，得到Configure对象
    // 调用attachAgent方法将代理加载到目标Java虚拟机
    private Arthas(String[] args) throws Exception {
        attachAgent(parse(args));
    }

    // 解析命令行参数的方法，返回Configure对象
    private Configure parse(String[] args) {
        // 定义必须的命令行选项：目标Java进程的PID
        Option pid = new TypedOption<Long>().setType(Long.class).setShortName("pid").setRequired(true);
        // 定义必须的命令行选项：Arthas核心JAR包的路径
        Option core = new TypedOption<String>().setType(String.class).setShortName("core").setRequired(true);
        // 定义必须的命令行选项：Arthas代理JAR包的路径
        Option agent = new TypedOption<String>().setType(String.class).setShortName("agent").setRequired(true);
        // 定义可选的命令行选项：目标JVM监听的IP地址
        Option target = new TypedOption<String>().setType(String.class).setShortName("target-ip");
        // 定义可选的命令行选项：目标JVM监听的Telnet端口
        Option telnetPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("telnet-port");
        // 定义可选的命令行选项：目标JVM监听的HTTP端口
        Option httpPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("http-port");
        // 定义可选的命令行选项：会话超时时间（秒）
        Option sessionTimeout = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("session-timeout");

        // 定义可选的命令行选项：用户名
        Option username = new TypedOption<String>().setType(String.class).setShortName("username");
        // 定义可选的命令行选项：密码
        Option password = new TypedOption<String>().setType(String.class).setShortName("password");

        // 定义可选的命令行选项：隧道服务器的URL
        Option tunnelServer = new TypedOption<String>().setType(String.class).setShortName("tunnel-server");
        // 定义可选的命令行选项：代理ID
        Option agentId = new TypedOption<String>().setType(String.class).setShortName("agent-id");
        // 定义可选的命令行选项：应用程序名称
        Option appName = new TypedOption<String>().setType(String.class).setShortName(ArthasConstants.APP_NAME);

        // 定义可选的命令行选项：统计信息上报的URL
        Option statUrl = new TypedOption<String>().setType(String.class).setShortName("stat-url");
        // 定义可选的命令行选项：禁用的命令列表
        Option disabledCommands = new TypedOption<String>().setType(String.class).setShortName("disabled-commands");

        // 创建命令行接口实例，并添加所有定义的选项
        CLI cli = CLIs.create("arthas").addOption(pid).addOption(core).addOption(agent).addOption(target)
                .addOption(telnetPort).addOption(httpPort).addOption(sessionTimeout)
                .addOption(username).addOption(password)
                .addOption(tunnelServer).addOption(agentId).addOption(appName).addOption(statUrl).addOption(disabledCommands);
        // 解析命令行参数数组，得到解析结果对象
        CommandLine commandLine = cli.parse(Arrays.asList(args));

        // 创建Configure对象，用于存储解析后的配置信息
        Configure configure = new Configure();
        // 设置目标Java进程的PID
        configure.setJavaPid((Long) commandLine.getOptionValue("pid"));
        // 设置Arthas代理JAR包的路径
        configure.setArthasAgent((String) commandLine.getOptionValue("agent"));
        // 设置Arthas核心JAR包的路径
        configure.setArthasCore((String) commandLine.getOptionValue("core"));
        // 如果命令行中指定了会话超时时间，则设置到Configure对象中
        if (commandLine.getOptionValue("session-timeout") != null) {
            configure.setSessionTimeout((Integer) commandLine.getOptionValue("session-timeout"));
        }

        // 如果命令行中指定了目标IP地址，则设置到Configure对象中
        if (commandLine.getOptionValue("target-ip") != null) {
            configure.setIp((String) commandLine.getOptionValue("target-ip"));
        }

        // 如果命令行中指定了Telnet端口，则设置到Configure对象中
        if (commandLine.getOptionValue("telnet-port") != null) {
            configure.setTelnetPort((Integer) commandLine.getOptionValue("telnet-port"));
        }
        // 如果命令行中指定了HTTP端口，则设置到Configure对象中
        if (commandLine.getOptionValue("http-port") != null) {
            configure.setHttpPort((Integer) commandLine.getOptionValue("http-port"));
        }

        // 设置用户名
        configure.setUsername((String) commandLine.getOptionValue("username"));
        // 设置密码
        configure.setPassword((String) commandLine.getOptionValue("password"));

        // 设置隧道服务器的URL
        configure.setTunnelServer((String) commandLine.getOptionValue("tunnel-server"));
        // 设置代理ID
        configure.setAgentId((String) commandLine.getOptionValue("agent-id"));
        // 设置统计信息上报的URL
        configure.setStatUrl((String) commandLine.getOptionValue("stat-url"));
        // 设置禁用的命令列表
        configure.setDisabledCommands((String) commandLine.getOptionValue("disabled-commands"));
        // 设置应用程序名称
        configure.setAppName((String) commandLine.getOptionValue(ArthasConstants.APP_NAME));
        return configure;
    }

    // 将代理加载到目标Java虚拟机的方法
    private void attachAgent(Configure configure) throws Exception {
        // 用于存储目标Java虚拟机的描述符
        VirtualMachineDescriptor virtualMachineDescriptor = null;
        // 遍历所有正在运行的Java虚拟机描述符
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            // 获取当前描述符对应的Java虚拟机的PID
            String pid = descriptor.id();
            // 如果当前PID与配置中的目标PID相等
            if (pid.equals(Long.toString(configure.getJavaPid()))) {
                // 记录目标Java虚拟机的描述符
                virtualMachineDescriptor = descriptor;
                // 跳出循环
                break;
            }
        }
        // 用于表示目标Java虚拟机的对象
        VirtualMachine virtualMachine = null;
        try {
            // 如果没有找到目标Java虚拟机的描述符
            if (null == virtualMachineDescriptor) {
                // 直接使用PID进行attach操作
                virtualMachine = VirtualMachine.attach("" + configure.getJavaPid());
            } else {
                // 使用描述符进行attach操作
                virtualMachine = VirtualMachine.attach(virtualMachineDescriptor);
            }

            // 获取目标Java虚拟机的系统属性
            Properties targetSystemProperties = virtualMachine.getSystemProperties();
            // 获取目标Java虚拟机的Java版本信息
            String targetJavaVersion = JavaVersionUtils.javaVersionStr(targetSystemProperties);
            // 获取当前Java虚拟机的Java版本信息
            String currentJavaVersion = JavaVersionUtils.javaVersionStr();
            // 如果目标Java版本和当前Java版本都不为空
            if (targetJavaVersion != null && currentJavaVersion != null) {
                // 如果两个版本不相等
                if (!targetJavaVersion.equals(currentJavaVersion)) {
                    // 打印警告信息，提示版本不匹配可能导致attach失败
                    AnsiLog.warn("Current VM java version: {} do not match target VM java version: {}, attach may fail.",
                            currentJavaVersion, targetJavaVersion);
                    // 打印警告信息，提示尝试设置相同的JAVA_HOME
                    AnsiLog.warn("Target VM JAVA_HOME is {}, arthas-boot JAVA_HOME is {}, try to set the same JAVA_HOME.",
                            targetSystemProperties.getProperty("java.home"), System.getProperty("java.home"));
                }
            }

            // 获取Arthas代理JAR包的路径
            String arthasAgentPath = configure.getArthasAgent();
            // 将Arthas代理JAR包的路径进行URL编码
            configure.setArthasAgent(encodeArg(arthasAgentPath));
            // 将Arthas核心JAR包的路径进行URL编码
            configure.setArthasCore(encodeArg(configure.getArthasCore()));
            try {
                // 将Arthas代理加载到目标Java虚拟机中，并传递核心JAR包路径和配置信息
                virtualMachine.loadAgent(arthasAgentPath,
                        configure.getArthasCore() + ";" + configure.toString());
            } catch (IOException e) {
                // 如果异常信息中包含特定字符串
                if (e.getMessage() != null && e.getMessage().contains("Non-numeric value found")) {
                    // 打印警告信息，提示可能是低版本JDK尝试attach高版本JDK
                    AnsiLog.warn(e);
                    AnsiLog.warn("It seems to use the lower version of JDK to attach the higher version of JDK.");
                    AnsiLog.warn(
                            "This error message can be ignored, the attach may have been successful, and it will still try to connect.");
                } else {
                    // 抛出异常
                    throw e;
                }
            } catch (com.sun.tools.attach.AgentLoadException ex) {
                // 如果异常信息为特定值
                if ("0".equals(ex.getMessage())) {
                    // 打印警告信息，提示可能是高版本JDK尝试attach低版本JDK
                    AnsiLog.warn(ex);
                    AnsiLog.warn("It seems to use the higher version of JDK to attach the lower version of JDK.");
                    AnsiLog.warn(
                            "This error message can be ignored, the attach may have been successful, and it will still try to connect.");
                } else {
                    // 抛出异常
                    throw ex;
                }
            }
        } finally {
            // 如果目标Java虚拟机对象不为空
            if (null != virtualMachine) {
                // 从目标Java虚拟机中分离
                virtualMachine.detach();
            }
        }
    }

    // 对参数进行URL编码的方法
    private static String encodeArg(String arg) {
        try {
            // 使用UTF-8编码对参数进行URL编码
            return URLEncoder.encode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            // 如果编码不支持，直接返回原始参数
            return arg;
        }
    }

    // 程序入口方法
    public static void main(String[] args) {
        try {
            // 创建Arthas对象，传入命令行参数
            new Arthas(args);
        } catch (Throwable t) {
            // 打印错误信息，提示Arthas启动失败
            AnsiLog.error("Start arthas failed, exception stack trace: ");
            // 打印异常堆栈信息
            t.printStackTrace();
            // 退出程序，返回错误码-1
            System.exit(-1);
        }
    }
}