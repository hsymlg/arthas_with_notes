package com.taobao.arthas.client;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetOptionHandler;
import org.apache.commons.net.telnet.WindowSizeOptionHandler;

import com.taobao.arthas.common.OSUtils;
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

import jline.Terminal;
import jline.TerminalSupport;
import jline.UnixTerminal;
import jline.console.ConsoleReader;
import jline.console.KeyMap;

/**
 * Arthas Telnet客户端主类
 * 实现与Arthas服务器的Telnet连接，支持交互式命令输入和批处理命令执行
 *
 * com.taobao.arthas.boot.Bootstrap启动的时候调用
 * com.taobao.arthas.boot.Bootstrap#findProcessByTelnetClient(java.lang.String, int)
 *
 * @author ralf0131 2016-12-29 11:55.
 * @author hengyunabc 2018-11-01
 */
@Name("arthas-client")
@Summary("Arthas Telnet Client")
@Description("EXAMPLES:\n" + "  java -jar arthas-client.jar 127.0.0.1 3658\n"
        + "  java -jar arthas-client.jar -c 'dashboard -n 1' \n"
        + "  java -jar arthas-client.jar -f batch.as 127.0.0.1\n")
public class TelnetConsole {
    // Arthas服务器提示符前缀，用于识别命令行输入提示
    private static final String PROMPT = "[arthas@"; // 格式如 [arthas@49603]$
    // 默认连接超时时间，单位毫秒
    private static final int DEFAULT_CONNECTION_TIMEOUT = 5000; // 5秒超时

    // Ctrl+C的ASCII码值，用于处理中断事件
    private static final byte CTRL_C = 0x03;

    // 状态码定义
    /** 命令执行成功 */
    public static final int STATUS_OK = 0;
    /** 通用错误状态码 */
    public static final int STATUS_ERROR = 1;
    /** 命令执行超时状态码 */
    public static final int STATUS_EXEC_TIMEOUT = 100;
    /** 命令执行错误状态码 */
    public static final int STATUS_EXEC_ERROR = 101;

    // 命令行参数属性
    private boolean help = false;                // 帮助标志，true表示显示帮助信息
    private String targetIp = "127.0.0.1";       // 目标服务器IP，默认本地回环地址
    private int port = 3658;                     // 目标服务器端口，默认3658
    private String command;                      // 要执行的命令，支持分号分隔多个命令
    private String batchFile;                    // 批处理文件路径
    private int executionTimeout = -1;           // 命令执行超时时间，-1表示不超时
    private Integer width = null;                // 终端宽度
    private Integer height = null;               // 终端高度

    /**
     * 设置目标服务器IP（命令行参数）
     * @param targetIp 目标服务器IP地址
     */
    @Argument(argName = "target-ip", index = 0, required = false)
    @Description("Target ip")
    public void setTargetIp(String targetIp) {
        this.targetIp = targetIp;
    }

    /**
     * 设置目标服务器端口（命令行参数）
     * @param port 目标服务器端口号
     */
    @Argument(argName = "port", index = 1, required = false)
    @Description("The remote server port")
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 设置帮助标志（命令行参数）
     * @param help 是否显示帮助信息
     */
    @Option(longName = "help", flag = true)
    @Description("Print usage")
    public void setHelp(boolean help) {
        this.help = help;
    }

    /**
     * 设置要执行的命令（命令行参数）
     * @param command 要执行的命令，多个命令用分号分隔
     */
    @Option(shortName = "c", longName = "command")
    @Description("Command to execute, multiple commands separated by ;")
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * 设置批处理文件路径（命令行参数）
     * @param batchFile 批处理文件路径
     */
    @Option(shortName = "f", longName = "batch-file")
    @Description("The batch file to execute")
    public void setBatchFile(String batchFile) {
        this.batchFile = batchFile;
    }

    /**
     * 设置命令执行超时时间（命令行参数）
     * @param executionTimeout 超时时间，单位毫秒
     */
    @Option(shortName = "t", longName = "execution-timeout")
    @Description("The timeout (ms) of execute commands or batch file ")
    public void setExecutionTimeout(int executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    /**
     * 设置终端宽度（命令行参数）
     * @param width 终端宽度
     */
    @Option(shortName = "w", longName = "width")
    @Description("The terminal width")
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * 设置终端高度（命令行参数）
     * @param height 终端高度
     */
    @Option(shortName = "h", longName = "height")
    @Description("The terminal height")
    public void setheight(int height) {
        this.height = height;
    }

    /**
     * 构造函数，初始化TelnetConsole实例
     */
    public TelnetConsole() {
    }

    /**
     * 从批处理文件读取命令行列表
     * @param batchFile 批处理文件对象
     * @return 包含所有命令行的列表，空行被忽略
     */
    private static List<String> readLines(File batchFile) {
        List<String> list = new ArrayList<String>();
        BufferedReader br = null;
        try {
            // 初始化文件读取器
            br = new BufferedReader(new FileReader(batchFile));
            String line;
            // 逐行读取文件内容
            while ((line = br.readLine()) != null) {
                list.add(line);
            }
        } catch (IOException e) {
            // 打印IO异常堆栈信息
            e.printStackTrace();
        } finally {
            // 确保关闭文件读取器
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }
        }
        return list;
    }

    /**
     * 程序入口方法，解析命令行参数并执行Telnet客户端
     * @param args 命令行参数数组
     * @throws Exception 处理过程中可能抛出的异常
     */
    public static void main(String[] args) throws Exception {
        try {
            // 处理命令行参数并执行，传入退出时的动作监听器
            int status = process(args, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // 正常退出，状态码为STATUS_OK
                    System.exit(STATUS_OK);
                }
            });
            // 使用处理结果状态码退出程序
            System.exit(status);
        } catch (Throwable e) {
            // 捕获所有可抛出异常并打印堆栈
            e.printStackTrace();
            // 定义命令行接口配置
            CLI cli = CLIConfigurator.define(TelnetConsole.class);
            // 打印用法说明
            System.out.println(usage(cli));
            // 异常退出，状态码为STATUS_ERROR
            System.exit(STATUS_ERROR);
        }
    }

    /**
     * 提供给arthas-boot调用的处理方法，无事件回调
     * @param args 命令行参数数组
     * @return 执行状态码
     * @throws IOException IO操作异常
     * @throws InterruptedException 线程中断异常
     */
    public static int process(String[] args) throws IOException, InterruptedException {
        return process(args, null);
    }

    /**
     * Telnet客户端主处理方法
     * @param args 命令行参数数组
     * @param eotEventCallback Ctrl+D事件回调（传输结束事件）
     * @return 执行状态码
     * @throws IOException IO操作异常
     */
    public static int process(String[] args, ActionListener eotEventCallback) throws IOException {
        // 处理Cygwin/MinGW环境下的jline颜色支持
        if (OSUtils.isCygwinOrMinGW()) {
            System.setProperty("jline.terminal", System.getProperty("jline.terminal", "jline.UnixTerminal"));
        }

        // 创建TelnetConsole实例
        TelnetConsole telnetConsole = new TelnetConsole();
        // 定义命令行接口配置
        CLI cli = CLIConfigurator.define(TelnetConsole.class);

        // 解析命令行参数
        CommandLine commandLine = cli.parse(Arrays.asList(args));
        // 将解析后的参数注入到telnetConsole实例
        CLIConfigurator.inject(commandLine, telnetConsole);

        // 如果用户请求帮助，打印用法并返回
        if (telnetConsole.isHelp()) {
            System.out.println(usage(cli));
            return STATUS_OK;
        }

        // 初始化命令列表
        List<String> cmds = new ArrayList<String>();
        // 处理命令行指定的命令
        if (telnetConsole.getCommand() != null) {
            // 按分号分割多个命令并添加到列表
            for (String c : telnetConsole.getCommand().split(";")) {
                cmds.add(c.trim());
            }
        }
        // 处理批处理文件指定的命令
        else if (telnetConsole.getBatchFile() != null) {
            File file = new File(telnetConsole.getBatchFile());
            if (!file.exists()) {
                throw new IllegalArgumentException("batch file do not exist: " + telnetConsole.getBatchFile());
            } else {
                // 从批处理文件读取命令并添加到列表
                cmds.addAll(readLines(file));
            }
        }

        // 创建控制台读取器，用于处理终端输入输出
        final ConsoleReader consoleReader = new ConsoleReader(System.in, System.out);
        // 设置处理用户中断
        consoleReader.setHandleUserInterrupt(true);
        // 获取终端对象
        Terminal terminal = consoleReader.getTerminal();

        // 禁用终端中断字符，支持捕获Ctrl+C
        terminal.disableInterruptCharacter();
        // 针对Unix终端特殊处理
        if (terminal instanceof UnixTerminal) {
            ((UnixTerminal) terminal).disableLitteralNextCharacter();
        }

        try {
            int width = TerminalSupport.DEFAULT_WIDTH;    // 初始化终端宽度为默认值
            int height = TerminalSupport.DEFAULT_HEIGHT;  // 初始化终端高度为默认值

            if (!cmds.isEmpty()) {
                // 批处理模式下使用用户指定的终端大小
                if (telnetConsole.getWidth() != null) {
                    width = telnetConsole.getWidth();
                }
                if (telnetConsole.getheight() != null) {
                    height = telnetConsole.getheight();
                }
            } else {
                // 交互模式下获取当前终端大小
                if (telnetConsole.getWidth() != null) {
                    width = telnetConsole.getWidth();
                } else {
                    width = terminal.getWidth();
                    // Windows系统特殊处理（调整宽度）
                    if (OSUtils.isWindows()) {
                        width--;
                    }
                }
                if (telnetConsole.getheight() != null) {
                    height = telnetConsole.getheight();
                } else {
                    height = terminal.getHeight();
                }
            }

            // 创建Telnet客户端实例
            final TelnetClient telnet = new TelnetClient();
            // 设置连接超时时间
            telnet.setConnectTimeout(DEFAULT_CONNECTION_TIMEOUT);

            // 创建终端大小选项处理器
            TelnetOptionHandler sizeOpt = new WindowSizeOptionHandler(width, height, true, true, false, false);
            try {
                // 添加终端大小选项到Telnet客户端
                telnet.addOptionHandler(sizeOpt);
            } catch (InvalidTelnetOptionException e) {
                // 忽略选项添加异常
            }

            // 绑定Ctrl+C事件处理
            consoleReader.getKeys().bind(Character.toString((char) CTRL_C), new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        consoleReader.getCursorBuffer().clear(); // 清除当前输入行
                        telnet.getOutputStream().write(CTRL_C);  // 发送Ctrl+C到服务器
                        telnet.getOutputStream().flush();       // 刷新输出流
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }
            });

            // 绑定Ctrl+D事件处理（EOT事件）
            consoleReader.getKeys().bind(Character.toString(KeyMap.CTRL_D), eotEventCallback);

            try {
                // 连接到Arthas服务器
                telnet.connect(telnetConsole.getTargetIp(), telnetConsole.getPort());
            } catch (IOException e) {
                System.out.println("Connect to telnet server error: " + telnetConsole.getTargetIp() + " "
                        + telnetConsole.getPort());
                throw e;
            }

            if (cmds.isEmpty()) {
                // 交互模式：读写输入输出流
                IOUtil.readWrite(telnet.getInputStream(), telnet.getOutputStream(), consoleReader.getInput(),
                        consoleReader.getOutput());
            } else {
                try {
                    // 批处理模式：执行批处理命令
                    return batchModeRun(telnet, cmds, telnetConsole.getExecutionTimeout());
                } catch (Throwable e) {
                    System.out.println("Execute commands error: " + e.getMessage());
                    e.printStackTrace();
                    return STATUS_EXEC_ERROR;
                } finally {
                    try {
                        // 断开Telnet连接
                        telnet.disconnect();
                    } catch (IOException e) {
                        // 忽略断开连接异常
                    }
                }
            }

            return STATUS_OK;
        } finally {
            // 重置终端设置，修复潜在问题
            try {
                terminal.restore();
            } catch (Throwable e) {
                System.out.println("Restore terminal settings failure: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 批处理模式命令执行方法
     * @param telnet Telnet客户端实例
     * @param commands 要执行的命令列表
     * @param executionTimeout 执行超时时间（毫秒）
     * @return 执行状态码
     * @throws IOException IO操作异常
     * @throws InterruptedException 线程中断异常
     */
    private static int batchModeRun(TelnetClient telnet, List<String> commands, final int executionTimeout)
            throws IOException, InterruptedException {
        if (commands.size() == 0) {
            return STATUS_OK;
        }

        long startTime = System.currentTimeMillis(); // 记录开始时间
        // 获取Telnet连接的输入输出流
        final InputStream inputStream = telnet.getInputStream();
        final OutputStream outputStream = telnet.getOutputStream();

        // 创建阻塞队列用于接收服务器提示符
        final BlockingQueue<String> receviedPromptQueue = new LinkedBlockingQueue<String>(1);
        // 创建线程用于读取服务器输出
        Thread printResultThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder line = new StringBuilder();                      // 用于拼接读取的字符
                    BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, "UTF-8")); // 输入流读取器
                    int b;                                                        // 存储读取的字符
                    while (true) {
                        b = in.read();                                            // 从输入流读取字符
                        if (b == -1) {                                           // 流结束时退出循环
                            break;
                        }
                        line.appendCodePoint(b);                                 // 追加字符到line

                        // 查找Arthas提示符位置
                        int index = line.indexOf(PROMPT);
                        if (index > 0) {                                          // 找到有效提示符
                            line.delete(0, index + PROMPT.length());              // 清除已处理的提示符部分
                            receviedPromptQueue.put("");                        // 通知主线程可以执行下一个命令
                        }
                        System.out.print(Character.toChars(b));                   // 输出字符到控制台
                    }
                } catch (Exception e) {
                    // 忽略所有异常
                }
            }
        });
        printResultThread.start(); // 启动输出读取线程

        // 循环发送命令到服务器
        for (String command : commands) {
            if (command.trim().isEmpty()) {
                continue; // 跳过空命令
            }
            // 等待服务器提示符，支持超时检查
            while (receviedPromptQueue.poll(100, TimeUnit.MILLISECONDS) == null) {
                if (executionTimeout > 0) {
                    long now = System.currentTimeMillis();
                    if (now - startTime > executionTimeout) {
                        return STATUS_EXEC_TIMEOUT; // 执行超时返回对应状态码
                    }
                }
            }
            // 发送命令到服务器（添加| plaintext确保纯文本输出）
            outputStream.write((command + " | plaintext\n").getBytes());
            outputStream.flush(); // 刷新输出流
        }

        // 等待最后一个命令的提示符，然后发送quit命令
        receviedPromptQueue.take();
        outputStream.write("quit\n".getBytes());
        outputStream.flush();
        System.out.println();

        return STATUS_OK;
    }

    /**
     * 生成命令行用法说明
     * @param cli 命令行接口配置
     * @return 格式化的用法说明字符串
     */
    private static String usage(CLI cli) {
        StringBuilder usageStringBuilder = new StringBuilder();
        UsageMessageFormatter usageMessageFormatter = new UsageMessageFormatter();
        usageMessageFormatter.setOptionComparator(null);
        cli.usage(usageStringBuilder, usageMessageFormatter);
        return UsageRender.render(usageStringBuilder.toString());
    }

    // ------- Getter方法，返回命令行参数值 ------- //
    public String getTargetIp() {
        return targetIp;
    }

    public int getPort() {
        return port;
    }

    public String getCommand() {
        return command;
    }

    public String getBatchFile() {
        return batchFile;
    }

    public int getExecutionTimeout() {
        return executionTimeout;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getheight() {
        return height;
    }

    public boolean isHelp() {
        return help;
    }
}