package com.taobao.arthas.common;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ServerSocketFactory;

/**
 *
 * @author hengyunabc 2018-11-07
 *
 */
public class SocketUtils {

    /**
     * The default minimum value for port ranges used when finding an available
     * socket port.
     */
    public static final int PORT_RANGE_MIN = 1024;

    /**
     * The default maximum value for port ranges used when finding an available
     * socket port.
     */
    public static final int PORT_RANGE_MAX = 65535;

    private static final Random random = new Random(System.currentTimeMillis());

    private SocketUtils() {
    }

    /**
     * 查找使用指定TCP端口进行监听的进程ID
     *
     * @param port 要检查的端口号
     * @return 占用该端口的进程ID，如果未找到则返回-1
     */
    public static long findTcpListenProcess(int port) {
        // 设置5秒超时时间，防止方法长时间阻塞
        final int TIMEOUT_SECONDS = 5;

        // 创建单线程执行器，用于异步执行端口检查逻辑
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // 提交一个异步任务，在后台执行实际的端口检查
            Future<Long> future = executor.submit(new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    // 调用实际的端口检查实现方法
                    return doFindTcpListenProcess(port);
                }
            });

            try {
                // 等待异步任务完成，最多等待5秒
                return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 超时处理：取消任务并返回-1表示未找到进程
                future.cancel(true);
                return -1;
            } catch (Exception e) {
                // 其他异常处理：返回-1表示发生错误
                return -1;
            }
        } finally {
            // 确保无论任务是否完成，都关闭执行器释放资源
            executor.shutdownNow();
        }
    }

    /**
     * 查找使用指定TCP端口进行监听的进程ID（底层实现）
     *
     * 根据操作系统类型调用相应的实现方法，支持Windows、Linux和MacOS
     *
     * @param port 要检查的端口号
     * @return 占用该端口的进程ID，如果未找到或发生错误则返回-1
     */
    private static long doFindTcpListenProcess(int port) {
        try {
            // 根据操作系统类型选择不同的实现方法
            if (OSUtils.isWindows()) {
                // 调用Windows系统下的端口检查方法
                return findTcpListenProcessOnWindows(port);
            }

            if (OSUtils.isLinux() || OSUtils.isMac()) {
                // 调用Unix/Linux/MacOS系统下的端口检查方法
                return findTcpListenProcessOnUnix(port);
            }
        } catch (Throwable e) {
            // 捕获并忽略所有异常，确保方法不会因为检查过程中的错误而中断
            // 异常发生时返回-1表示未找到进程
        }
        // 默认返回-1表示未找到或发生错误
        return -1;
    }

    /**
     * 在Windows系统上查找使用指定TCP端口进行监听的进程ID
     *
     * 通过执行netstat命令并解析输出结果来查找占用端口的进程
     *
     * @param port 要检查的端口号
     * @return 占用该端口的进程ID，如果未找到则返回-1
     */
    private static long findTcpListenProcessOnWindows(int port) {
        // 构建netstat命令，-ano参数表示显示所有连接、活动监听和进程ID
        String[] command = { "netstat", "-ano", "-p", "TCP" };

        // 执行本地命令并获取输出结果
        List<String> lines = ExecutingCommand.runNative(command);

        // 遍历命令输出的每一行
        for (String line : lines) {
            // 查找包含"LISTENING"的行，这些行表示正在监听的端口
            if (line.contains("LISTENING")) {
                // 示例行格式: "TCP 0.0.0.0:49168 0.0.0.0:0 LISTENING 476"
                // 使用一个或多个空格作为分隔符分割字符串
                String[] strings = line.trim().split("\\s+");

                // 检查分割后的数组长度是否为5（正确的行格式）
                if (strings.length == 5) {
                    // 检查第二个元素（本地地址和端口）是否以目标端口结尾
                    if (strings[1].endsWith(":" + port)) {
                        // 如果匹配，返回第五个元素（进程ID）
                        return Long.parseLong(strings[4]);
                    }
                }
            }
        }

        // 如果没有找到匹配的进程，返回-1
        return -1;
    }

    /**
     * 在Unix/Linux/MacOS系统上查找使用指定TCP端口进行监听的进程ID
     *
     * 通过执行lsof命令并解析输出结果来查找占用端口的进程
     * lsof (list open files) 是Unix系统上用于显示当前系统打开文件的工具
     *
     * @param port 要检查的端口号
     * @return 占用该端口的进程ID，如果未找到则返回-1
     */
    private static long findTcpListenProcessOnUnix(int port) {
        // 构建lsof命令，参数说明：
        // -t: 只输出进程ID (PID)
        // -s TCP:LISTEN: 只显示TCP监听状态的连接
        // -i TCP:port: 只显示TCP协议且端口号匹配的连接
        String pid = ExecutingCommand.getFirstAnswer("lsof -t -s TCP:LISTEN -i TCP:" + port);

        // 检查命令输出是否不为空
        if (pid != null && !pid.trim().isEmpty()) {
            try {
                // 尝试将输出结果转换为长整型（进程ID）
                return Long.parseLong(pid.trim());
            } catch (NumberFormatException e) {
                // 忽略数字格式异常，表明输出不是有效的进程ID
                // 异常发生时将返回-1
            }
        }

        // 如果没有找到匹配的进程或发生错误，返回-1
        return -1;
    }

    public static boolean isTcpPortAvailable(int port) {
        try {
            ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(port, 1,
                    InetAddress.getByName("localhost"));
            serverSocket.close();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Find an available TCP port randomly selected from the range
     * [{@value #PORT_RANGE_MIN}, {@value #PORT_RANGE_MAX}].
     * 
     * @return an available TCP port number
     * @throws IllegalStateException if no available port could be found
     */
    public static int findAvailableTcpPort() {
        return findAvailableTcpPort(PORT_RANGE_MIN);
    }

    /**
     * Find an available TCP port randomly selected from the range [{@code minPort},
     * {@value #PORT_RANGE_MAX}].
     * 
     * @param minPort the minimum port number
     * @return an available TCP port number
     * @throws IllegalStateException if no available port could be found
     */
    public static int findAvailableTcpPort(int minPort) {
        return findAvailableTcpPort(minPort, PORT_RANGE_MAX);
    }

    /**
     * Find an available TCP port randomly selected from the range [{@code minPort},
     * {@code maxPort}].
     * 
     * @param minPort the minimum port number
     * @param maxPort the maximum port number
     * @return an available TCP port number
     * @throws IllegalStateException if no available port could be found
     */
    public static int findAvailableTcpPort(int minPort, int maxPort) {
        return findAvailablePort(minPort, maxPort);
    }

    /**
     * Find an available port for this {@code SocketType}, randomly selected from
     * the range [{@code minPort}, {@code maxPort}].
     * 
     * @param minPort the minimum port number
     * @param maxPort the maximum port number
     * @return an available port number for this socket type
     * @throws IllegalStateException if no available port could be found
     */
    private static int findAvailablePort(int minPort, int maxPort) {

        int portRange = maxPort - minPort;
        int candidatePort;
        int searchCounter = 0;
        do {
            if (searchCounter > portRange) {
                throw new IllegalStateException(
                        String.format("Could not find an available tcp port in the range [%d, %d] after %d attempts",
                                minPort, maxPort, searchCounter));
            }
            candidatePort = findRandomPort(minPort, maxPort);
            searchCounter++;
        } while (!isTcpPortAvailable(candidatePort));

        return candidatePort;
    }

    /**
     * Find a pseudo-random port number within the range [{@code minPort},
     * {@code maxPort}].
     * 
     * @param minPort the minimum port number
     * @param maxPort the maximum port number
     * @return a random port number within the specified range
     */
    private static int findRandomPort(int minPort, int maxPort) {
        int portRange = maxPort - minPort;
        return minPort + random.nextInt(portRange + 1);
    }
}
