package com.taobao.arthas.agent334;

// 引入SpyAPI类，用于判断Arthas是否已经初始化
import java.arthas.SpyAPI;
// 引入File类，用于文件操作
import java.io.File;
// 引入FileOutputStream类，用于将数据写入文件
import java.io.FileOutputStream;
// 引入PrintStream类，用于输出日志信息
import java.io.PrintStream;
// 引入UnsupportedEncodingException类，用于处理编码不支持的异常
import java.io.UnsupportedEncodingException;
// 引入Instrumentation类，用于Java Agent的字节码增强
import java.lang.instrument.Instrumentation;
// 引入URL类，用于处理URL地址
import java.net.URL;
// 引入URLDecoder类，用于对URL编码的字符串进行解码
import java.net.URLDecoder;
// 引入CodeSource类，用于获取类的代码源信息
import java.security.CodeSource;

// 引入自定义的Arthas类加载器
import com.taobao.arthas.agent.ArthasClassloader;

/**
 * 代理启动类
 *
 * @author vlinux on 15/5/19.
 */
public class AgentBootstrap {
    // 定义Arthas核心JAR包的文件名
    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    // 定义Arthas启动类的全限定名
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    // 定义获取Arthas启动类实例的方法名
    private static final String GET_INSTANCE = "getInstance";
    // 定义判断Arthas服务器是否绑定成功的方法名
    private static final String IS_BIND = "isBind";

    // 定义一个PrintStream对象，用于输出日志信息，初始化为标准错误输出
    private static PrintStream ps = System.err;
    static {
        try {
            // 创建Arthas日志目录对象，路径为用户主目录下的logs/arthas目录
            File arthasLogDir = new File(System.getProperty("user.home") + File.separator + "logs" + File.separator
                    + "arthas" + File.separator);
            // 如果日志目录不存在，则创建该目录
            if (!arthasLogDir.exists()) {
                arthasLogDir.mkdirs();
            }
            // 如果日志目录仍然不存在，尝试在临时目录下创建logs/arthas目录
            if (!arthasLogDir.exists()) {
                // #572
                arthasLogDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "logs" + File.separator
                        + "arthas" + File.separator);
                // 如果临时目录下的日志目录不存在，则创建该目录
                if (!arthasLogDir.exists()) {
                    arthasLogDir.mkdirs();
                }
            }

            // 创建Arthas日志文件对象，路径为日志目录下的arthas.log文件
            File log = new File(arthasLogDir, "arthas.log");

            // 如果日志文件不存在，则创建该文件
            if (!log.exists()) {
                log.createNewFile();
            }
            // 将PrintStream对象指向日志文件，以追加模式写入日志信息
            ps = new PrintStream(new FileOutputStream(log, true));
        } catch (Throwable t) {
            // 如果出现异常，将异常信息输出到日志文件
            t.printStackTrace(ps);
        }
    }

    /**
     * <pre>
     * 1. 全局持有classloader用于隔离 Arthas 实现，防止多次attach重复初始化
     * 2. ClassLoader在arthas停止时会被reset
     * 3. 如果ClassLoader一直没变，则 com.taobao.arthas.core.server.ArthasBootstrap#getInstance 返回结果一直是一样的
     * </pre>
     */
    // 定义一个volatile修饰的ClassLoader对象，用于加载Arthas相关类
    private static volatile ClassLoader arthasClassLoader;

    /**
     * JVM启动时，在main方法执行之前调用该方法
     *
     * JVM 启动阶段的类加载顺序
     * 启动类加载器（Bootstrap ClassLoader）：加载 JVM 核心类（如java.lang.Object）。
     * 扩展类加载器（Extension ClassLoader）：加载jre/lib/ext下的类。
     * 应用程序类加载器（AppClassLoader）：加载应用程序的类路径（classpath）中的类，包括主类（含main方法的类）。
     *
     * 当使用-javaagent参数时，代理 JAR 包会被优先加载，其premain方法会在应用程序类加载器工作之前执行。（main方法里的agentLoader做到了优先加载）
     * 这使得代理可以在类加载阶段就介入，修改字节码（如 Arthas 的类增强），而不影响应用程序的正常逻辑。
     *
     * src/hotspot/share/prims/jvm.cpp
     * JVM_InitAgent 函数：负责加载代理 JAR 包并调用 premain 方法
     *
     * @param args 传递给代理的参数
     * @param inst Instrumentation对象，用于字节码增强
     */
    public static void premain(String args, Instrumentation inst) {
        // 调用main方法处理代理启动逻辑
        main(args, inst);
    }

    /**
     * 动态attach到JVM时调用该方法
     *
     * premain	JVM 启动时，main方法之前	启动时即需要字节码增强的场景
     * agentmain	JVM 运行时，通过attach机制动态调用	运行时动态添加代理（如 Arthas 连接已启动的 JVM）
     *
     * @param args 传递给代理的参数
     * @param inst Instrumentation对象，用于字节码增强
     */
    public static void agentmain(String args, Instrumentation inst) {
        // 调用main方法处理代理启动逻辑
        main(args, inst);
    }

    /**
     * 让下次再次启动时有机会重新加载
     */
    public static void resetArthasClassLoader() {
        // 将arthasClassLoader置为null，以便下次重新加载
        arthasClassLoader = null;
    }

    /**
     * 获取用于加载Arthas相关类的ClassLoader
     *
     * @param inst              Instrumentation对象，用于字节码增强
     * @param arthasCoreJarFile Arthas核心JAR包的文件对象
     * @return 用于加载Arthas相关类的ClassLoader
     * @throws Throwable 如果获取ClassLoader过程中出现异常
     */
    private static ClassLoader getClassLoader(Instrumentation inst, File arthasCoreJarFile) throws Throwable {
        // 构造自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
        return loadOrDefineClassLoader(arthasCoreJarFile);
    }

    /**
     * 加载或定义ClassLoader
     *
     * @param arthasCoreJarFile Arthas核心JAR包的文件对象
     * @return 用于加载Arthas相关类的ClassLoader
     * @throws Throwable 如果加载或定义ClassLoader过程中出现异常
     */
    private static ClassLoader loadOrDefineClassLoader(File arthasCoreJarFile) throws Throwable {
        // 如果arthasClassLoader为null，则创建一个新的ArthasClassloader对象
        if (arthasClassLoader == null) {
            arthasClassLoader = new ArthasClassloader(new URL[]{arthasCoreJarFile.toURI().toURL()});
        }
        // 返回arthasClassLoader
        return arthasClassLoader;
    }

    /**
     * 主方法，处理代理启动逻辑
     * synchronized的原因：虽然 premain 是单线程调用的，但是agentmain也在调用main,Arthas 被多次 attach（例如用户误操作），可能导致多个线程同时调用 main 方法。
     *
     * Arthas 作为 Java Agent，通过java -jar arthas.jar或java -agentlib方式附着到目标 JVM。此时：
     * 主线程 (启动 Arthas 的线程) 属于应用程序的类加载环境，使用应用类加载器
     * Arthas 自身的类由AgentClassLoader加载，与应用类加载器属于不同的类加载层级
     * Arthas 通过 “子线程 + 专属 TCL” 的方式，在不干扰应用程序的前提下，确保自身逻辑在独立的类加载环境中执行
     *
     * @param args 传递给代理的参数
     * @param inst Instrumentation对象，用于字节码增强
     */
    private static synchronized void main(String args, final Instrumentation inst) {
        // 尝试判断arthas是否已在运行，如果是的话，直接就退出
        try {
            // 尝试加载java.arthas.SpyAPI类，如果加载不到会抛异常
            Class.forName("java.arthas.SpyAPI");
            // 判断SpyAPI是否已经初始化
            if (SpyAPI.isInited()) {
                // 如果已经初始化，输出日志信息并退出
                ps.println("Arthas server already stared, skip attach.");
                ps.flush();
                return;
            }
        } catch (Throwable e) {
            // 忽略异常
        }
        try {
            // 输出Arthas服务器代理启动的日志信息
            ps.println("Arthas server agent start...");
            // 传递的args参数分两个部分:arthasCoreJar路径和agentArgs, 分别是Agent的JAR包路径和期望传递到服务端的参数
            if (args == null) {
                args = "";
            }
            // 对参数进行URL解码
            // 确保包含空格、& 等特殊字符的参数能被正确传递
            // 假设java -javaagent:/path/to/arthas-agent.jar="/path/to/arthas-core.jar;option=value with space" -jar myapp.jar
            // 参数中的空格和路径分隔符需要编码为"/path/to/arthas-core.jar;option=value%20with%20space"
            args = decodeArg(args);

            String arthasCoreJar;
            final String agentArgs;
            // 查找参数中分隔符;的位置
            int index = args.indexOf(';');
            if (index != -1) {
                // 如果找到分隔符，则将参数分割为arthasCoreJar路径和agentArgs
                arthasCoreJar = args.substring(0, index);
                agentArgs = args.substring(index);
            } else {
                // 如果未找到分隔符，则arthasCoreJar为空，agentArgs为全部参数
                arthasCoreJar = "";
                agentArgs = args;
            }

            // 创建Arthas核心JAR包的文件对象
            File arthasCoreJarFile = new File(arthasCoreJar);
            // 如果Arthas核心JAR包文件不存在
            if (!arthasCoreJarFile.exists()) {
                // 输出找不到Arthas核心JAR包文件的日志信息
                ps.println("Can not find arthas-core jar file from args: " + arthasCoreJarFile);
                // 尝试从arthas-agent.jar所在目录查找Arthas核心JAR包
                // 在 Arthas 的标准部署中，arthas-agent.jar 和 arthas-core.jar 通常位于同一目录下，因此通过获取 agent JAR 的父目录即可定位到核心 JAR。
                // 位于同一目录的原因是详见方法com.taobao.arthas.boot.DownloadUtils.downArthasPackaging
                CodeSource codeSource = AgentBootstrap.class.getProtectionDomain().getCodeSource();
                if (codeSource != null) {
                    try {
                        // 获取arthas-agent.jar的文件对象
                        File arthasAgentJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                        // 从arthas-agent.jar所在目录查找Arthas核心JAR包
                        arthasCoreJarFile = new File(arthasAgentJarFile.getParentFile(), ARTHAS_CORE_JAR);
                        if (!arthasCoreJarFile.exists()) {
                            // 如果仍然找不到，输出日志信息
                            ps.println("Can not find arthas-core jar file from agent jar directory: " + arthasAgentJarFile);
                        }
                    } catch (Throwable e) {
                        // 如果出现异常，输出日志信息并打印异常堆栈
                        ps.println("Can not find arthas-core jar file from " + codeSource.getLocation());
                        e.printStackTrace(ps);
                    }
                }
            }
            // 如果最终还是找不到Arthas核心JAR包文件，则直接返回
            if (!arthasCoreJarFile.exists()) {
                return;
            }

            /**
             * Use a dedicated thread to run the binding logic to prevent possible memory leak. #195
             */
            // 获取用于加载Arthas相关类的ClassLoader
            final ClassLoader agentLoader = getClassLoader(inst, arthasCoreJarFile);

            // 创建一个新的线程来执行绑定逻辑
            //
            Thread bindingThread = new Thread() {
                @Override
                public void run() {
                    try {
                        // 调用bind方法进行绑定操作
                        bind(inst, agentLoader, agentArgs);
                    } catch (Throwable throwable) {
                        // 如果出现异常，将异常信息输出到日志文件
                        throwable.printStackTrace(ps);
                    }
                }
            };

            // 设置线程名称
            bindingThread.setName("arthas-binding-thread");
            // 启动线程
            bindingThread.start();
            try {
                // 等待线程执行完毕
                bindingThread.join();
            } catch (InterruptedException e) {
                // 如果线程被中断，恢复中断状态
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            // 如果出现异常，将异常信息输出到日志文件
            t.printStackTrace(ps);
            try {
                // 如果PrintStream对象不是标准错误输出，则关闭该对象
                if (ps != System.err) {
                    ps.close();
                }
            } catch (Throwable tt) {
                // 忽略异常
            }
            // 抛出运行时异常
            throw new RuntimeException(t);
        }
    }

    /**
     * 绑定Arthas服务器
     *
     * Arthas 通过自定义类加载器（如ArthasClassloader）加载自身的类，该类加载器与应用程序的类加载器（AppClassLoader）属于不同层级或隔离的上下文。
     * 在编译期，编译器要求直接引用的类必须在当前类路径中存在，但 Arthas 的类通常不会被包含在应用程序的编译类路径中。
     * 应用程序的类加载器（AppClassLoader）无法直接访问由 Arthas 自定义类加载器加载的类。如果在编译期直接引用ArthasBootstrap，会导致编译器报错（如ClassNotFoundException），因为编译期无法解析该类的引用。
     * Agent 的工作机制要求在 JVM 启动后或运行时动态加载 Arthas 的类。通过反射（ClassLoader.loadClass和Method.invoke）可以在运行时由 Arthas 的类加载器动态加载ArthasBootstrap，避免编译期依赖。
     *
     * @param inst      Instrumentation对象，用于字节码增强
     * @param agentLoader 用于加载Arthas相关类的ClassLoader
     * @param args      传递给服务端的参数
     * @throws Throwable 如果绑定过程中出现异常
     */
    private static void bind(Instrumentation inst, ClassLoader agentLoader, String args) throws Throwable {
        /**
         * <pre>
         * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(inst);
         * </pre>
         */
        // 使用ClassLoader加载Arthas启动类（确保ArthasBootstrap类由正确的类加载器加载，避免类冲突）
        Class<?> bootstrapClass = agentLoader.loadClass(ARTHAS_BOOTSTRAP);
        // 调用Arthas启动类的getInstance方法获取实例（调用静态方法，第一个参数为null，传递Instrumentation实例和参数，初始化 Arthas 服务端）
        Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, Instrumentation.class, String.class).invoke(null, inst, args);
        // 调用Arthas启动类的isBind方法判断服务器是否绑定成功
        boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
        if (!isBind) {
            // 如果绑定失败，输出错误信息并抛出运行时异常
            String errorMsg = "Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.";
            ps.println(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        // 如果绑定成功，输出日志信息
        ps.println("Arthas server already bind.");
    }

    /**
     * 对参数进行URL解码
     *
     * @param arg 待解码的参数
     * @return 解码后的参数
     */
    private static String decodeArg(String arg) {
        try {
            // 使用UTF-8编码对参数进行URL解码
            return URLDecoder.decode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            // 如果编码不支持，则返回原始参数
            return arg;
        }
    }
}