package com.taobao.arthas.boot;
// 包声明，指定该类所属的包结构

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.InputMismatchException;
// 导入该类所需的所有外部类，涵盖文件操作、IO流、反射、网络、集合等功能模块

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.ExecutingCommand;
import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.common.PidUtils;
// 导入Arthas自定义的工具类，用于日志记录、命令执行、IO操作、Java版本判断和进程ID获取

/**
 * 进程工具类，提供Java进程查找、Arthas核心启动、客户端连接等功能
 *
 * @author hengyunabc 2018-11-06
 */
public class ProcessUtils {
    // 类定义，ProcessUtils 作为Arthas的进程处理核心工具类

    private static String FOUND_JAVA_HOME = null;
    // 静态变量，缓存找到的Java Home路径，避免重复查找提升性能

    // 定义Arthas客户端返回的状态码，用于标识操作结果
    /** 处理成功 */
    public static final int STATUS_OK = 0;
    /** 通用错误 */
    public static final int STATUS_ERROR = 1;
    /** 执行命令超时 */
    public static final int STATUS_EXEC_TIMEOUT = 100;
    /** 执行命令错误 */
    public static final int STATUS_EXEC_ERROR = 101;

    /**
     * 根据用户输入或命令行参数选择目标Java进程
     *
     * @param v 是否显示JVM参数（用于jps命令）
     * @param telnetPortPid 占用Telnet端口的进程ID
     * @param select 命令行指定的进程筛选关键字
     * @return 选择的进程ID，未找到或错误返回-1
     * @throws InputMismatchException 输入格式错误时抛出
     */
    @SuppressWarnings("resource") // 抑制资源未关闭警告，Scanner会自动关闭
    public static long select(boolean v, long telnetPortPid, String select) throws InputMismatchException {
        // 通过jps命令获取Java进程列表
        Map<Long, String> processMap = listProcessByJps(v);

        // 将占用Telnet端口的进程移到列表开头，方便用户选择
        if (telnetPortPid > 0 && processMap.containsKey(telnetPortPid)) {
            String telnetPortProcess = processMap.get(telnetPortPid);
            processMap.remove(telnetPortPid);
            Map<Long, String> newProcessMap = new LinkedHashMap<Long, String>();
            newProcessMap.put(telnetPortPid, telnetPortProcess);
            newProcessMap.putAll(processMap);
            processMap = newProcessMap;
        }

        // 检查进程列表是否为空，为空则提示用户
        if (processMap.isEmpty()) {
            AnsiLog.info("未找到Java进程。尝试运行`jps`命令列出目标系统上的Java虚拟机。");
            return -1;
        }

        // 通过'--select'选项筛选进程（当只有一个匹配时直接选择）
        if (select != null && !select.trim().isEmpty()) {
            int matchedSelectCount = 0;
            Long matchedPid = null;
            for (Entry<Long, String> entry : processMap.entrySet()) {
                if (entry.getValue().contains(select)) {
                    matchedSelectCount++;
                    matchedPid = entry.getKey();
                }
            }
            if (matchedSelectCount == 1) {
                return matchedPid;
            }
        }

        // 提示用户选择进程，并打印进程列表
        AnsiLog.info("找到现有Java进程，请选择一个并输入进程的序号，例如: 1。然后按ENTER。");
        int count = 1;
        for (String process : processMap.values()) {
            if (count == 1) {
                System.out.println("* [" + count + "]: " + process); // 标记第一个进程
            } else {
                System.out.println("  [" + count + "]: " + process);
            }
            count++;
        }

        // 读取用户输入的选择
        String line = new Scanner(System.in).nextLine();
        if (line.trim().isEmpty()) {
            // 输入为空时，默认选择第一个进程
            return processMap.keySet().iterator().next();
        }

        // 解析用户输入的序号
        int choice = new Scanner(line).nextInt();

        // 验证选择的序号是否有效
        if (choice <= 0 || choice > processMap.size()) {
            return -1;
        }

        // 根据序号获取对应的进程ID
        Iterator<Long> idIter = processMap.keySet().iterator();
        for (int i = 1; i <= choice; ++i) {
            if (i == choice) {
                return idIter.next();
            }
            idIter.next();
        }

        return -1;
    }

    /**
     * 通过jps命令列出Java进程
     *
     * 该方法使用jps命令获取系统中运行的Java进程列表，
     * 支持过滤当前进程自身和jps命令进程，确保返回的是目标Java进程
     *
     * @param v 是否显示JVM参数（-v选项）
     * @return 进程ID到进程信息的映射（LinkedHashMap保持顺序）
     */
    private static Map<Long, String> listProcessByJps(boolean v) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        // 使用LinkedHashMap保存结果，确保插入顺序（便于后续用户选择）

        // 查找jps命令的路径，优先使用找到的绝对路径
        String jps = "jps";
        File jpsFile = findJps();
        if (jpsFile != null) {
            jps = jpsFile.getAbsolutePath();
            // 使用绝对路径调用jps命令，避免PATH环境变量影响
        }

        AnsiLog.debug("尝试使用jps列出Java进程，jps路径: " + jps);

        // 构建jps命令参数
        // -l: 输出主类的完整包名或JAR文件的完整路径名
        // -v: 输出JVM参数（如果v为true）
        String[] command = null;
        if (v) {
            command = new String[] { jps, "-v", "-l" };
        } else {
            command = new String[] { jps, "-l" };
        }

        // 执行jps命令并获取输出结果
        List<String> lines = ExecutingCommand.runNative(command);
        // runNative方法执行本地命令并返回输出行列表

        AnsiLog.debug("jps命令执行结果: " + lines);

        // 获取当前进程ID，用于过滤自身
        long currentPid = Long.parseLong(PidUtils.currentPid());
        // PidUtils.currentPid()返回当前进程的PID字符串

        // 解析jps命令输出的每一行
        for (String line : lines) {
            String[] strings = line.trim().split("\\s+");
            // 使用正则表达式"\s+"分割字符串，处理多个空格的情况

            if (strings.length < 1) {
                continue; // 跳过空行
            }

            try {
                // 解析进程ID
                long pid = Long.parseLong(strings[0]);

                // 过滤当前进程自身
                if (pid == currentPid) {
                    continue;
                }

                // 过滤jps命令进程本身
                if (strings.length >= 2 && isJpsProcess(strings[1])) {
                    // isJpsProcess方法判断是否为jps命令的主类
                    continue;
                }

                // 将有效进程添加到结果映射中
                result.put(pid, line);
                // key: 进程ID，value: jps输出的完整行（包含进程信息）

            } catch (Throwable e) {
                // 忽略解析异常，继续处理下一个进程
                // 处理jps输出可能的格式问题（如特殊字符、不规范输出等）
                AnsiLog.debug("解析jps输出时发生异常，跳过该行: " + line, e);
            }
        }

        return result;
    }

    /**
     * 查找Java Home路径，优先使用系统属性，其次环境变量（处理不同Java版本差异）
     *
     * @return 找到的Java Home路径
     */
    public static String findJavaHome() {
        if (FOUND_JAVA_HOME != null) {
            return FOUND_JAVA_HOME; // 直接返回缓存的Java Home路径
        }

        // 首先从系统属性获取java.home
        String javaHome = System.getProperty("java.home");

        // 对于Java 8及以下版本，需要确保存在tools.jar（Java 9+不需要）
        if (JavaVersionUtils.isLessThanJava9()) {
            File toolsJar = new File(javaHome, "lib/tools.jar");
            if (!toolsJar.exists()) {
                toolsJar = new File(javaHome, "../lib/tools.jar"); // 尝试上级目录
            }
            if (!toolsJar.exists()) {
                // 可能是jre环境，尝试更上级目录
                toolsJar = new File(javaHome, "../../lib/tools.jar");
            }

            // 找到tools.jar，使用当前java.home
            if (toolsJar.exists()) {
                FOUND_JAVA_HOME = javaHome;
                return FOUND_JAVA_HOME;
            }

            // 未找到tools.jar，尝试从环境变量JAVA_HOME查找
            if (!toolsJar.exists()) {
                AnsiLog.debug("在java.home下未找到tools.jar: " + javaHome);
                String javaHomeEnv = System.getenv("JAVA_HOME");
                if (javaHomeEnv != null && !javaHomeEnv.isEmpty()) {
                    AnsiLog.debug("尝试在系统环境变量JAVA_HOME中查找tools.jar: " + javaHomeEnv);
                    toolsJar = new File(javaHomeEnv, "lib/tools.jar");
                    if (!toolsJar.exists()) {
                        toolsJar = new File(javaHomeEnv, "../lib/tools.jar"); // 尝试上级目录
                    }
                }

                // 在JAVA_HOME中找到tools.jar，使用JAVA_HOME
                if (toolsJar.exists()) {
                    AnsiLog.info("从系统环境变量JAVA_HOME找到java home: " + javaHomeEnv);
                    FOUND_JAVA_HOME = javaHomeEnv;
                    return FOUND_JAVA_HOME;
                }

                // 未找到tools.jar，抛出异常并提示用户使用完整路径启动
                throw new IllegalArgumentException("在java home下未找到tools.jar: " + javaHome
                        + "，请尝试使用完整路径的java启动arthas-boot。例如 /opt/jdk/bin/java -jar arthas-boot.jar");
            }
        } else {
            // Java 9及以上版本，直接使用java.home（无需tools.jar）
            FOUND_JAVA_HOME = javaHome;
        }
        return FOUND_JAVA_HOME;
    }

    /**
     * 启动Arthas核心到目标Java进程（通过Java进程附加方式）
     *
     * 该方法通过启动一个新的Java进程，使用Java Attach API将Arthas核心库注入目标进程，
     * 实现非侵入式诊断功能。适用于Java 6及以上版本，支持不同Java环境的自动适配。
     *
     * @param targetPid 目标Java进程的PID
     * @param attachArgs 附加参数（包含Arthas核心启动配置，如端口、IP、核心库路径等）
     */
    public static void startArthasCore(long targetPid, List<String> attachArgs) {
        // --------------------- 环境准备阶段 ---------------------
        // 查找Java Home路径（优先系统属性，其次环境变量，自动处理Java版本差异）
        String javaHome = findJavaHome();
        // findJavaHome方法会自动：
        // 1. 从System.getProperty("java.home")获取
        // 2. 检查Java 8及以下是否存在tools.jar
        // 3. 若不存在则尝试从环境变量JAVA_HOME查找
        // 4. 最终返回有效的Java Home路径

        // 在Java Home中查找java可执行文件（支持不同系统和目录结构）
        File javaPath = findJava(javaHome);
        if (javaPath == null) {
            // 未找到java可执行文件时抛出异常并提示用户
            throw new IllegalArgumentException(
                    "在java home下未找到java/java.exe可执行文件: " + javaHome);
        }

        // 查找tools.jar（仅Java 8及以下需要，Java 9+不需要）
        File toolsJar = findToolsJar(javaHome);

        // 检查Java版本并验证tools.jar存在性（Java 8及以下必需）
        if (JavaVersionUtils.isLessThanJava9()) {
            if (toolsJar == null || !toolsJar.exists()) {
                throw new IllegalArgumentException("在java home下未找到tools.jar: " + javaHome);
            }
        }

        // --------------------- 命令构建阶段 ---------------------
        // 初始化启动命令列表（从java可执行文件开始）
        List<String> command = new ArrayList<String>();
        command.add(javaPath.getAbsolutePath());
        // 添加java可执行文件的绝对路径，确保跨平台兼容性

        // 若存在tools.jar（Java 8及以下），添加到引导类路径
        if (toolsJar != null && toolsJar.exists()) {
            command.add("-Xbootclasspath/a:" + toolsJar.getAbsolutePath());
            // -Xbootclasspath/a: 表示将指定路径添加到引导类路径的末尾
            // 用于在Java 8及以下环境中加载Arthas所需的附加类
        }

        // 添加Arthas核心启动参数（由调用方传入，如端口、IP、核心库路径等）
        command.addAll(attachArgs);
        // attachArgs通常包含：
        // -jar arthas-core.jar
        // -pid ${targetPid}
        // -telnet-port ${port}
        // 等Arthas核心启动参数

        // --------------------- 进程启动阶段 ---------------------
        // 创建进程构建器并配置环境变量
        ProcessBuilder pb = new ProcessBuilder(command);
        // 清空JAVA_TOOL_OPTIONS环境变量，避免外部配置干扰Arthas启动
        pb.environment().put("JAVA_TOOL_OPTIONS", "");

        try {
            // 启动Java进程（执行构建好的命令）
            final Process proc = pb.start();

            // --------------------- 输出重定向阶段 ---------------------
            // 创建线程重定向子进程的标准输出到当前进程的标准输出
            Thread redirectStdout = new Thread(new Runnable() {
                @Override
                public void run() {
                    //相对于当前进程而言的，而非子进程，这个需要注意，当前进程的输入
                    InputStream inputStream = proc.getInputStream();
                    try {
                        // 使用IOUtils.copy将子进程输出复制到System.out
                        IOUtils.copy(inputStream, System.out);
                    } catch (IOException e) {
                        // 异常时关闭输入流
                        IOUtils.close(inputStream);
                    }
                }
            });

            // 创建线程重定向子进程的标准错误到当前进程的标准错误
            Thread redirectStderr = new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream inputStream = proc.getErrorStream();
                    try {
                        // 使用IOUtils.copy将子进程错误输出复制到System.err
                        IOUtils.copy(inputStream, System.err);
                    } catch (IOException e) {
                        // 异常时关闭输入流
                        IOUtils.close(inputStream);
                    }
                }
            });

            // 启动重定向线程
            redirectStdout.start();
            redirectStderr.start();
            // 等待重定向线程完成（确保输出全部读取）
            redirectStdout.join();
            redirectStderr.join();

            // --------------------- 进程状态检查阶段 ---------------------
            // 获取子进程退出码
            int exitValue = proc.exitValue();
            if (exitValue != 0) {
                // 非0退出码表示Arthas核心启动失败
                AnsiLog.error("attach失败，目标PID: " + targetPid);
                System.exit(1); // 退出当前进程，终止启动流程
            }
        } catch (Throwable e) {
            // 捕获所有可能的异常（如IOException、InterruptedException等）
            // 由于Arthas是诊断工具，此处忽略异常以避免阻塞主流程
            // 实际错误会通过子进程输出重定向显示
        }
    }


    /**
     * 启动Arthas客户端（Telnet连接工具）
     *
     * @param arthasHomeDir Arthas主目录
     * @param telnetArgs Telnet连接参数（如IP、端口）
     * @param out 输出流（用于重定向客户端输出）
     * @return 执行状态码（参考STATUS_常量）
     * @throws Throwable 反射调用可能抛出的异常
     */
    public static int startArthasClient(String arthasHomeDir, List<String> telnetArgs, OutputStream out) throws Throwable {
        // 加载arthas-client.jar并创建类加载器
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{new File(arthasHomeDir, "arthas-client.jar").toURI().toURL()});
        Class<?> telnetConsoleClass = classLoader.loadClass("com.taobao.arthas.client.TelnetConsole");
        Method processMethod = telnetConsoleClass.getMethod("process", String[].class);

        // 重定向System.out/System.err到指定输出流
        PrintStream originSysOut = System.out;
        PrintStream originSysErr = System.err;
        PrintStream newOut = new PrintStream(out);
        PrintStream newErr = new PrintStream(out);

        // 保存当前线程上下文类加载器（后续恢复）
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            System.setOut(newOut);
            System.setErr(newErr);
            Thread.currentThread().setContextClassLoader(classLoader);
            // 通过反射调用TelnetConsole的process方法
            return (Integer) processMethod.invoke(null, new Object[]{telnetArgs.toArray(new String[0])});
        } catch (Throwable e) {
            // 处理反射调用中的异常（获取实际 cause）
            e = e.getCause();
            if (e instanceof IOException || e instanceof InterruptedException) {
                // 忽略连接错误和中断错误
                return STATUS_ERROR;
            } else {
                // 其他错误记录日志并返回错误状态码
                AnsiLog.error("处理错误: {}", e.toString());
                AnsiLog.error(e);
                return STATUS_EXEC_ERROR;
            }
        } finally {
            // 恢复线程上下文类加载器和System.out/System.err
            Thread.currentThread().setContextClassLoader(tccl);
            System.setOut(originSysOut);
            System.setErr(originSysErr);
            newOut.flush();
            newErr.flush(); // 刷新输出流确保数据输出
        }
    }

    /**
     * 在Java Home中查找java可执行文件（支持不同路径格式）
     *
     * @param javaHome Java Home路径
     * @return 找到的java可执行文件，未找到返回null
     */
    private static File findJava(String javaHome) {
        // 定义可能的java可执行文件路径（支持bin/java和bin/java.exe等）
        String[] paths = { "bin/java", "bin/java.exe", "../bin/java", "../bin/java.exe" };

        List<File> javaList = new ArrayList<File>();
        for (String path : paths) {
            File javaFile = new File(javaHome, path);
            if (javaFile.exists()) {
                AnsiLog.debug("找到java可执行文件: " + javaFile.getAbsolutePath());
                javaList.add(javaFile);
            }
        }

        // 未找到java可执行文件
        if (javaList.isEmpty()) {
            AnsiLog.debug("在当前java home下未找到java/java.exe: " + javaHome);
            return null;
        }

        // 找到多个java可执行文件时，选择路径最短的（通常jre路径比jdk长）
        if (javaList.size() > 1) {
            Collections.sort(javaList, new Comparator<File>() {
                @Override
                public int compare(File file1, File file2) {
                    try {
                        return file1.getCanonicalPath().length() - file2.getCanonicalPath().length();
                    } catch (IOException e) {
                        // 忽略异常，默认返回-1
                    }
                    return -1;
                }
            });
        }
        return javaList.get(0); // 返回路径最短的java可执行文件
    }

    /**
     * 在Java Home中查找tools.jar（仅Java 8及以下需要）
     *
     * @param javaHome Java Home路径
     * @return 找到的tools.jar文件
     */
    private static File findToolsJar(String javaHome) {
        // Java 9及以上版本不需要tools.jar
        if (JavaVersionUtils.isGreaterThanJava8()) {
            return null;
        }

        // 查找tools.jar可能的路径（当前目录、上级目录等）
        File toolsJar = new File(javaHome, "lib/tools.jar");
        if (!toolsJar.exists()) {
            toolsJar = new File(javaHome, "../lib/tools.jar");
        }
        if (!toolsJar.exists()) {
            // 可能是jre环境，尝试更上级目录
            toolsJar = new File(javaHome, "../../lib/tools.jar");
        }

        // 未找到tools.jar，抛出异常
        if (!toolsJar.exists()) {
            throw new IllegalArgumentException("在java home下未找到tools.jar: " + javaHome);
        }

        AnsiLog.debug("找到tools.jar: " + toolsJar.getAbsolutePath());
        return toolsJar;
    }

    /**
     * 查找jps命令（用于列出Java进程）
     *
     * 该方法会先在java.home中查找jps命令，若未找到则尝试在环境变量JAVA_HOME中查找，
     * 支持Windows/Linux/MacOS不同系统的路径格式，返回路径最短的有效jps命令
     *
     * @return 找到的jps命令文件，未找到返回null
     */
    private static File findJps() {
        // 首先在System.getProperty("java.home")中查找jps命令
        String javaHome = System.getProperty("java.home");
        // 定义jps命令可能的路径（支持不同系统和目录结构）
        String[] paths = { "bin/jps", "bin/jps.exe", "../bin/jps", "../bin/jps.exe" };

        // 存储找到的jps命令文件
        List<File> jpsList = new ArrayList<File>();
        // 遍历所有可能的路径
        for (String path : paths) {
            File jpsFile = new File(javaHome, path);
            if (jpsFile.exists()) {
                AnsiLog.debug("在java.home中找到jps命令: " + jpsFile.getAbsolutePath());
                jpsList.add(jpsFile); // 添加到结果列表
            }
        }

        // 在java.home中未找到jps命令，尝试在环境变量JAVA_HOME中查找
        if (jpsList.isEmpty()) {
            AnsiLog.debug("在java.home路径下未找到jps: " + javaHome);
            String javaHomeEnv = System.getenv("JAVA_HOME");
            AnsiLog.debug("尝试在环境变量JAVA_HOME中查找jps: " + javaHomeEnv);
            // 再次遍历路径，使用JAVA_HOME作为基础路径
            for (String path : paths) {
                File jpsFile = new File(javaHomeEnv, path);
                if (jpsFile.exists()) {
                    AnsiLog.debug("在JAVA_HOME中找到jps命令: " + jpsFile.getAbsolutePath());
                    jpsList.add(jpsFile); // 添加到结果列表
                }
            }
        }

        // 未找到任何jps命令
        if (jpsList.isEmpty()) {
            AnsiLog.debug("在当前java home下未找到jps: " + javaHome);
            return null;
        }

        // 找到多个jps命令时，选择路径最短的（通常jre路径比jdk长，优先使用jdk中的jps）
        if (jpsList.size() > 1) {
            Collections.sort(jpsList, new Comparator<File>() {
                @Override
                public int compare(File file1, File file2) {
                    try {
                        // 按文件规范路径的长度排序
                        return file1.getCanonicalPath().length() - file2.getCanonicalPath().length();
                    } catch (IOException e) {
                        AnsiLog.debug("获取文件路径时发生异常，使用默认排序", e);
                    }
                    return -1; // 默认为file1优先
                }
            });
        }
        return jpsList.get(0); // 返回路径最短的jps命令文件
    }

    /**
     * 判断是否为jps进程本身（避免将jps进程作为目标进程）
     *
     * @param mainClassName 主类名
     * @return true如果是jps进程，否则false
     */
    private static boolean isJpsProcess(String mainClassName) {
        // 检查主类名是否为jps的主类（支持Java 8和Java 9+的不同格式）
        return "sun.tools.jps.Jps".equals(mainClassName) || "jdk.jcmd/sun.tools.jps.Jps".equals(mainClassName);
    }
}