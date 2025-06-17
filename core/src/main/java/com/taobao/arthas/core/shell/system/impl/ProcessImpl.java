// 声明该类所在的包
package com.taobao.arthas.core.shell.system.impl;

// 引入阿里巴巴Arthas依赖的SLF4J日志框架
import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
// 引入Arthas的AdviceListener和AdviceWeaver类，用于处理方法增强的监听和织入
import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.advisor.AdviceWeaver;
// 引入Arthas的HelpCommand类，用于处理帮助命令
import com.taobao.arthas.core.command.basic1000.HelpCommand;
// 引入Arthas的ResultModel和StatusModel类，用于表示命令执行结果和状态
import com.taobao.arthas.core.command.model.ResultModel;
import com.taobao.arthas.core.command.model.StatusModel;
// 引入Arthas的ResultDistributor和TermResultDistributorImpl类，用于结果分发
import com.taobao.arthas.core.distribution.ResultDistributor;
import com.taobao.arthas.core.distribution.impl.TermResultDistributorImpl;
// 引入Arthas的ArthasBootstrap类，用于启动和管理Arthas服务
import com.taobao.arthas.core.server.ArthasBootstrap;
// 引入Arthas的CliToken类，用于表示命令行参数的令牌
import com.taobao.arthas.core.shell.cli.CliToken;
// 引入Arthas的Command和CommandProcess类，用于表示命令和命令处理过程
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.CommandProcess;
// 引入Arthas的CloseFunction和StatisticsFunction类，用于处理关闭和统计功能
import com.taobao.arthas.core.shell.command.internal.CloseFunction;
import com.taobao.arthas.core.shell.command.internal.StatisticsFunction;
// 引入Arthas的Handler类，用于处理各种事件
import com.taobao.arthas.core.shell.handlers.Handler;
// 引入Arthas的Session类，用于表示会话
import com.taobao.arthas.core.shell.session.Session;
// 引入Arthas的ExecStatus和Process类，用于表示执行状态和进程
import com.taobao.arthas.core.shell.system.ExecStatus;
import com.taobao.arthas.core.shell.system.Process;
// 引入Arthas的ProcessAware类，用于表示进程感知能力
import com.taobao.arthas.core.shell.system.ProcessAware;
// 引入Arthas的Tty类，用于表示终端
import com.taobao.arthas.core.shell.term.Tty;
// 引入淘宝中间件的CLIException和CommandLine类，用于处理命令行解析异常和命令行
import com.taobao.middleware.cli.CLIException;
import com.taobao.middleware.cli.CommandLine;
// 引入termd库的Function类，用于表示函数式接口
import io.termd.core.function.Function;

// 引入Java的类文件转换器接口，用于动态修改类文件
import java.lang.instrument.ClassFileTransformer;
// 引入Java的日期类，用于记录时间
import java.util.Date;
// 引入Java的链表类，用于存储数据
import java.util.LinkedList;
// 引入Java的列表接口，用于表示列表
import java.util.List;
// 引入Java的原子整数类，用于线程安全的计数
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 该类实现了Process接口，用于表示一个进程
 * @author beiwei30 on 10/11/2016.
 * @author gongdewei 2020-03-26
 */
public class ProcessImpl implements Process {

    // 创建一个SLF4J的日志记录器，用于记录该类的日志信息
    private static final Logger logger = LoggerFactory.getLogger(ProcessImpl.class);

    // 命令上下文，包含了命令的详细信息
    private Command commandContext;
    // 命令处理的处理器，用于处理命令执行过程中的事件
    private Handler<CommandProcess> handler;
    // 命令行参数的令牌列表，存储了命令行输入的参数信息
    private List<CliToken> args;
    // 终端对象，用于与用户进行交互
    private Tty tty;
    // 会话对象，用于管理会话状态
    private Session session;
    // 中断事件处理器，当进程被中断时调用
    private Handler<Void> interruptHandler;
    // 暂停事件处理器，当进程被暂停时调用
    private Handler<Void> suspendHandler;
    // 恢复事件处理器，当进程恢复执行时调用
    private Handler<Void> resumeHandler;
    // 结束事件处理器，当进程结束时调用
    private Handler<Void> endHandler;
    // 后台运行事件处理器，当进程被设置为后台运行时调用
    private Handler<Void> backgroundHandler;
    // 前台运行事件处理器，当进程被设置为前台运行时调用
    private Handler<Void> foregroundHandler;
    // 终止事件处理器，当进程被终止时调用
    private Handler<Integer> terminatedHandler;
    // 表示进程是否在前台运行的标志
    private boolean foreground;
    // 进程的执行状态，使用ExecStatus枚举表示
    private ExecStatus processStatus;
    // 表示进程是否处于前台执行的标志
    private boolean processForeground;
    // 标准输入事件处理器，用于处理标准输入的事件
    private Handler<String> stdinHandler;
    // 终端大小改变事件处理器，当终端大小改变时调用
    private Handler<Void> resizeHandler;
    // 进程的退出码，用于表示进程的退出状态
    private Integer exitCode;
    // 命令处理的实现对象，用于处理命令的具体执行
    private CommandProcessImpl process;
    // 进程的开始时间，记录进程开始执行的时间
    private Date startTime;
    // 进程的输出对象，用于处理进程的输出信息
    private ProcessOutput processOutput;
    // 作业ID，用于标识进程所属的作业
    private int jobId;
    // 结果分发器，用于分发命令执行的结果
    private ResultDistributor resultDistributor;

    /**
     * 构造函数，初始化进程的相关信息
     * @param commandContext 命令上下文
     * @param args 命令行参数的令牌列表
     * @param handler 命令处理的处理器
     * @param processOutput 进程的输出对象
     * @param resultDistributor 结果分发器
     */
    public ProcessImpl(Command commandContext, List<CliToken> args, Handler<CommandProcess> handler,
                       ProcessOutput processOutput, ResultDistributor resultDistributor) {
        // 初始化命令上下文
        this.commandContext = commandContext;
        // 初始化命令处理的处理器
        this.handler = handler;
        // 初始化命令行参数的令牌列表
        this.args = args;
        // 初始化结果分发器
        this.resultDistributor = resultDistributor;
        // 初始化进程的执行状态为就绪状态
        this.processStatus = ExecStatus.READY;
        // 初始化进程的输出对象
        this.processOutput = processOutput;
    }

    /**
     * 获取进程的退出码
     * @return 进程的退出码
     */
    @Override
    public Integer exitCode() {
        return exitCode;
    }

    /**
     * 获取进程的执行状态
     * @return 进程的执行状态
     */
    @Override
    public ExecStatus status() {
        return processStatus;
    }

    /**
     * 设置进程的终端对象，使用同步方法确保线程安全
     * @param tty 终端对象
     * @return 当前进程对象
     */
    @Override
    public synchronized Process setTty(Tty tty) {
        // 设置终端对象
        this.tty = tty;
        return this;
    }

    /**
     * 获取进程的终端对象，使用同步方法确保线程安全
     * @return 终端对象
     */
    @Override
    public synchronized Tty getTty() {
        return tty;
    }

    /**
     * 设置进程所属的作业ID
     * @param jobId 作业ID
     */
    @Override
    public void setJobId(int jobId) {
        this.jobId = jobId;
    }

    /**
     * 设置进程的会话对象，使用同步方法确保线程安全
     * @param session 会话对象
     * @return 当前进程对象
     */
    @Override
    public synchronized Process setSession(Session session) {
        // 设置会话对象
        this.session = session;
        return this;
    }

    /**
     * 获取进程的会话对象，使用同步方法确保线程安全
     * @return 会话对象
     */
    @Override
    public synchronized Session getSession() {
        return session;
    }

    /**
     * 获取进程的执行次数
     * @return 进程的执行次数
     */
    @Override
    public int times() {
        return process.times().get();
    }

    /**
     * 获取进程的开始时间
     * @return 进程的开始时间
     */
    public Date startTime() {
        return startTime;
    }

    /**
     * 获取进程的缓存位置
     * @return 进程的缓存位置，如果不存在则返回null
     */
    @Override
    public String cacheLocation() {
        if (processOutput != null) {
            return processOutput.cacheLocation;
        }
        return null;
    }

    /**
     * 设置进程的终止事件处理器
     * @param handler 终止事件处理器
     * @return 当前进程对象
     */
    @Override
    public Process terminatedHandler(Handler<Integer> handler) {
        // 设置终止事件处理器
        terminatedHandler = handler;
        return this;
    }

    /**
     * 中断进程，调用带完成处理器的中断方法
     * @return 如果中断成功则返回true，否则返回false
     */
    @Override
    public boolean interrupt() {
        return interrupt(null);
    }

    /**
     * 中断进程，根据进程的执行状态决定是否可以中断
     * @param completionHandler 完成处理器，在中断操作完成后调用
     * @return 如果中断成功则返回true，否则返回false
     */
    @Override
    public boolean interrupt(final Handler<Void> completionHandler) {
        // 只有当进程处于运行或停止状态时才能中断
        if (processStatus == ExecStatus.RUNNING || processStatus == ExecStatus.STOPPED) {
            // 获取中断事件处理器
            final Handler<Void> handler = interruptHandler;
            try {
                // 如果中断事件处理器不为空，则调用它处理中断事件
                if (handler != null) {
                    handler.handle(null);
                }
            } finally {
                // 如果完成处理器不为空，则调用它处理完成事件
                if (completionHandler != null) {
                    completionHandler.handle(null);
                }
            }
            // 返回中断事件处理器是否存在的标志
            return handler != null;
        } else {
            // 如果进程处于不允许中断的状态，则抛出异常
            throw new IllegalStateException("Cannot interrupt process in " + processStatus + " state");
        }
    }

    /**
     * 恢复进程执行，调用带前台标志和完成处理器的恢复方法，默认前台恢复
     */
    @Override
    public void resume() {
        resume(true);
    }

    /**
     * 恢复进程执行，调用带完成处理器的恢复方法
     * @param foreground 是否在前台恢复
     */
    @Override
    public void resume(boolean foreground) {
        resume(foreground, null);
    }

    /**
     * 恢复进程执行，调用带前台标志和完成处理器的恢复方法，默认前台恢复
     * @param completionHandler 完成处理器，在恢复操作完成后调用
     */
    @Override
    public void resume(Handler<Void> completionHandler) {
        resume(true, completionHandler);
    }

    /**
     * 恢复进程执行，根据进程的执行状态决定是否可以恢复
     * @param fg 是否在前台恢复
     * @param completionHandler 完成处理器，在恢复操作完成后调用
     */
    @Override
    public synchronized void resume(boolean fg, Handler<Void> completionHandler) {
        // 只有当进程处于停止状态时才能恢复
        if (processStatus == ExecStatus.STOPPED) {
            // 更新进程的执行状态、退出码、前台标志等信息
            updateStatus(ExecStatus.RUNNING, null, fg, resumeHandler, terminatedHandler, completionHandler);
            // 如果命令处理实现对象不为空，则调用它的恢复方法
            if (process != null) {
                process.resume();
            }
        } else {
            // 如果进程处于不允许恢复的状态，则抛出异常
            throw new IllegalStateException("Cannot resume process in " + processStatus + " state");
        }
    }

    /**
     * 暂停进程执行，调用带完成处理器的暂停方法
     */
    @Override
    public void suspend() {
        suspend(null);
    }

    /**
     * 暂停进程执行，根据进程的执行状态决定是否可以暂停
     * @param completionHandler 完成处理器，在暂停操作完成后调用
     */
    @Override
    public synchronized void suspend(Handler<Void> completionHandler) {
        // 只有当进程处于运行状态时才能暂停
        if (processStatus == ExecStatus.RUNNING) {
            // 更新进程的执行状态、退出码、前台标志等信息
            updateStatus(ExecStatus.STOPPED, null, false, suspendHandler, terminatedHandler, completionHandler);
            // 如果命令处理实现对象不为空，则调用它的暂停方法
            if (process != null) {
                process.suspend();
            }
        } else {
            // 如果进程处于不允许暂停的状态，则抛出异常
            throw new IllegalStateException("Cannot suspend process in " + processStatus + " state");
        }
    }

    /**
     * 将进程设置为后台运行，调用带完成处理器的后台运行方法
     */
    @Override
    public void toBackground() {
        toBackground(null);
    }

    /**
     * 将进程设置为后台运行，根据进程的执行状态和前台标志决定是否可以设置
     * @param completionHandler 完成处理器，在设置操作完成后调用
     */
    @Override
    public void toBackground(Handler<Void> completionHandler) {
        // 只有当进程处于运行状态且在前台执行时才能设置为后台运行
        if (processStatus == ExecStatus.RUNNING) {
            if (processForeground) {
                // 更新进程的执行状态、退出码、前台标志等信息
                updateStatus(ExecStatus.RUNNING, null, false, backgroundHandler, terminatedHandler, completionHandler);
            }
        } else {
            // 如果进程处于不允许设置为后台运行的状态，则抛出异常
            throw new IllegalStateException("Cannot set to background a process in " + processStatus + " state");
        }
    }

    /**
     * 将进程设置为前台运行，调用带完成处理器的前台运行方法
     */
    @Override
    public void toForeground() {
        toForeground(null);
    }

    /**
     * 将进程设置为前台运行，根据进程的执行状态和前台标志决定是否可以设置
     * @param completionHandler 完成处理器，在设置操作完成后调用
     */
    @Override
    public void toForeground(Handler<Void> completionHandler) {
        // 只有当进程处于运行状态且不在前台执行时才能设置为前台运行
        if (processStatus == ExecStatus.RUNNING) {
            if (!processForeground) {
                // 更新进程的执行状态、退出码、前台标志等信息
                updateStatus(ExecStatus.RUNNING, null, true, foregroundHandler, terminatedHandler, completionHandler);
            }
        } else {
            // 如果进程处于不允许设置为前台运行的状态，则抛出异常
            throw new IllegalStateException("Cannot set to foreground a process in " + processStatus + " state");
        }
    }

    /**
     * 终止进程，调用带完成处理器的终止方法
     */
    @Override
    public void terminate() {
        terminate(null);
    }

    /**
     * 终止进程，根据进程的执行状态决定是否可以终止
     * @param completionHandler 完成处理器，在终止操作完成后调用
     */
    @Override
    public void terminate(Handler<Void> completionHandler) {
        // 调用私有终止方法，设置退出码为-10
        if (!terminate(-10, completionHandler, null)) {
            // 如果进程已经终止，则抛出异常
            throw new IllegalStateException("Cannot terminate terminated process");
        }
    }

    /**
     * 终止进程的私有方法，根据进程的执行状态决定是否可以终止
     * @param exitCode 退出码
     * @param completionHandler 完成处理器，在终止操作完成后调用
     * @param message 终止消息
     * @return 如果终止成功则返回true，否则返回false
     */
    private synchronized boolean terminate(int exitCode, Handler<Void> completionHandler, String message) {
        // 只有当进程未处于终止状态时才能终止
        if (processStatus != ExecStatus.TERMINATED) {
            // 添加状态消息到结果中
            this.appendResult(new StatusModel(exitCode, message));
            // 如果命令处理实现对象不为空，则关闭进程的输出对象
            if (process != null) {
                processOutput.close();
            }
            // 更新进程的执行状态、退出码、前台标志等信息
            updateStatus(ExecStatus.TERMINATED, exitCode, false, endHandler, terminatedHandler, completionHandler);
            // 如果命令处理实现对象不为空，则注销相关资源
            if (process != null) {
                process.unregister();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * 将结果添加到结果分发器中
     * @param result 结果模型
     */
    private void appendResult(ResultModel result) {
        // 设置结果的作业ID
        result.setJobId(jobId);
        // 如果结果分发器不为空，则将结果添加到分发器中
        if (resultDistributor != null) {
            resultDistributor.appendResult(result);
        }
    }

    /**
     * 更新进程的执行状态、退出码、前台标志等信息
     * @param statusUpdate 新的执行状态
     * @param exitCodeUpdate 新的退出码
     * @param foregroundUpdate 新的前台标志
     * @param handler 状态更新处理器
     * @param terminatedHandler 终止事件处理器
     * @param completionHandler 完成处理器
     */
    private void updateStatus(ExecStatus statusUpdate, Integer exitCodeUpdate, boolean foregroundUpdate,
                              Handler<Void> handler, Handler<Integer> terminatedHandler,
                              Handler<Void> completionHandler) {
        // 更新进程的执行状态
        processStatus = statusUpdate;
        // 更新进程的退出码
        exitCode = exitCodeUpdate;
        // 根据前台标志更新进程的前台执行状态和相关处理器
        if (!foregroundUpdate) {
            if (processForeground) {
                processForeground = false;
                if (stdinHandler != null) {
                    tty.stdinHandler(null);
                }
                if (resizeHandler != null) {
                    tty.resizehandler(null);
                }
            }
        } else {
            if (!processForeground) {
                processForeground = true;
                if (stdinHandler != null) {
                    tty.stdinHandler(stdinHandler);
                }
                if (resizeHandler != null) {
                    tty.resizehandler(resizeHandler);
                }
            }
        }

        // 更新前台标志
        foreground = foregroundUpdate;
        try {
            // 如果状态更新处理器不为空，则调用它处理状态更新事件
            if (handler != null) {
                handler.handle(null);
            }
        } finally {
            // 如果完成处理器不为空，则调用它处理完成事件
            if (completionHandler != null) {
                completionHandler.handle(null);
            }
            // 如果终止事件处理器不为空且进程状态为终止状态，则调用它处理终止事件
            if (terminatedHandler != null && statusUpdate == ExecStatus.TERMINATED) {
                terminatedHandler.handle(exitCodeUpdate);
            }
        }
    }

    /**
     * 运行进程，调用带前台标志的运行方法，默认前台运行
     */
    @Override
    public void run() {
        run(true);
    }

    /**
     * 运行进程，根据进程的执行状态决定是否可以运行
     * @param fg 是否在前台运行
     */
    @Override
    public synchronized void run(boolean fg) {
        // 只有当进程处于就绪状态时才能运行
        if (processStatus != ExecStatus.READY) {
            throw new IllegalStateException("Cannot run proces in " + processStatus + " state");
        }

        // 更新进程的执行状态和前台标志
        processStatus = ExecStatus.RUNNING;
        processForeground = fg;
        foreground = fg;
        // 记录进程的开始时间
        startTime = new Date();

        // 获取终端对象的本地副本
        final Tty tty = this.tty;
        if (tty == null) {
            // 如果终端对象为空，则抛出异常
            throw new IllegalStateException("Cannot execute process without a TTY set");
        }

        // 创建命令处理的实现对象
        process = new CommandProcessImpl(this, tty);
        if (resultDistributor == null) {
            // 如果结果分发器为空，则创建一个新的终端结果分发器
            resultDistributor = new TermResultDistributorImpl(process, ArthasBootstrap.getInstance().getResultViewResolver());
        }

        // 将命令行参数的令牌列表转换为字符串列表
        final List<String> args2 = new LinkedList<String>();
        for (CliToken arg : args) {
            if (arg.isText()) {
                args2.add(arg.value());
            }
        }

        // 解析命令行参数
        CommandLine cl = null;
        try {
            if (commandContext.cli() != null) {
                if (commandContext.cli().parse(args2, false).isAskingForHelp()) {
                    // 如果用户请求帮助信息，则添加帮助信息到结果中并终止进程
                    appendResult(new HelpCommand().createHelpDetailModel(commandContext));
                    terminate();
                    return;
                }

                // 解析命令行参数
                cl = commandContext.cli().parse(args2);
                // 设置命令处理实现对象的参数和命令行
                process.setArgs2(args2);
                process.setCommandLine(cl);
            }
        } catch (CLIException e) {
            // 如果解析命令行参数时发生异常，则终止进程并输出错误信息
            terminate(-10, null, e.getMessage());
            return;
        }

        // 如果进程有缓存位置，则输出作业ID和缓存位置信息
        if (cacheLocation() != null) {
            process.echoTips("job id  : " + this.jobId + "\n");
            process.echoTips("cache location  : " + cacheLocation() + "\n");
        }
        // 创建一个命令处理任务
        Runnable task = new CommandProcessTask(process);
        // 执行命令处理任务
        ArthasBootstrap.getInstance().execute(task);
    }

    /**
     * 命令处理任务类，实现了Runnable接口，用于执行命令处理任务
     */
    private class CommandProcessTask implements Runnable {

        // 命令处理对象
        private CommandProcess process;

        /**
         * 构造函数，初始化命令处理对象
         * @param process 命令处理对象
         */
        public CommandProcessTask(CommandProcess process) {
            this.process = process;
        }

        /**
         * 任务执行方法，调用命令处理的处理器处理命令
         */
        @Override
        public void run() {
            try {
                // 调用命令处理的处理器处理命令
                handler.handle(process);
            } catch (Throwable t) {
                // 如果处理命令时发生异常，则记录错误日志并结束命令处理
                logger.error("Error during processing the command:", t);
                process.end(1, "Error during processing the command: " + t.getClass().getName() + ", message:" + t.getMessage()
                        + ", please check $HOME/logs/arthas/arthas.log for more details." );
            }
        }
    }

    /**
     * 命令处理的实现类，实现了CommandProcess接口
     */
    private class CommandProcessImpl implements CommandProcess {

        // 进程对象
        private final Process process;
        // 终端对象
        private final Tty tty;
        // 命令行参数的字符串列表
        private List<String> args2;
        // 命令行对象
        private CommandLine commandLine;
        // 命令执行次数的原子整数
        private AtomicInteger times = new AtomicInteger();
        // 建议监听器
        private AdviceListener listener = null;
        // 类文件转换器
        private ClassFileTransformer transformer;

        /**
         * 构造函数，初始化进程对象和终端对象
         * @param process 进程对象
         * @param tty 终端对象
         */
        public CommandProcessImpl(Process process, Tty tty) {
            this.process = process;
            this.tty = tty;
        }

        /**
         * 获取命令行参数的令牌列表
         * @return 命令行参数的令牌列表
         */
        @Override
        public List<CliToken> argsTokens() {
            return args;
        }

        /**
         * 获取命令行参数的字符串列表
         * @return 命令行参数的字符串列表
         */
        @Override
        public List<String> args() {
            return args2;
        }

        /**
         * 获取终端的类型
         * @return 终端的类型
         */
        @Override
        public String type() {
            return tty.type();
        }

        /**
         * 判断进程是否在前台运行
         * @return 如果在前台运行则返回true，否则返回false
         */
        @Override
        public boolean isForeground() {
            return foreground;
        }

        /**
         * 获取终端的宽度
         * @return 终端的宽度
         */
        @Override
        public int width() {
            return tty.width();
        }

        /**
         * 获取终端的高度
         * @return 终端的高度
         */
        @Override
        public int height() {
            return tty.height();
        }

        /**
         * 获取命令行对象
         * @return 命令行对象
         */
        @Override
        public CommandLine commandLine() {
            return commandLine;
        }

        /**
         * 获取会话对象
         * @return 会话对象
         */
        @Override
        public Session session() {
            return session;
        }

        /**
         * 获取命令执行次数的原子整数
         * @return 命令执行次数的原子整数
         */
        @Override
        public AtomicInteger times() {
            return times;
        }

        /**
         * 设置命令行参数的字符串列表
         * @param args2 命令行参数的字符串列表
         */
        public void setArgs2(List<String> args2) {
            this.args2 = args2;
        }

        /**
         * 设置命令行对象
         * @param commandLine 命令行对象
         */
        public void setCommandLine(CommandLine commandLine) {
            this.commandLine = commandLine;
        }

        /**
         * 设置标准输入事件处理器
         * @param handler 标准输入事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess stdinHandler(Handler<String> handler) {
            // 设置标准输入事件处理器
            stdinHandler = handler;
            if (processForeground && stdinHandler != null) {
                // 如果进程在前台执行且标准输入事件处理器不为空，则设置终端的标准输入处理器
                tty.stdinHandler(stdinHandler);
            }
            return this;
        }

        /**
         * 向标准输出写入数据
         * @param data 要写入的数据
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess write(String data) {
            synchronized (ProcessImpl.this) {
                if (processStatus != ExecStatus.RUNNING) {
                    // 如果进程不处于运行状态，则抛出异常
                    throw new IllegalStateException(
                            "Cannot write to standard output when " + status().name().toLowerCase());
                }
            }
            // 调用进程输出对象的写入方法写入数据
            processOutput.write(data);
            return this;
        }

        /**
         * 输出提示信息
         * @param tips 提示信息
         */
        @Override
        public void echoTips(String tips) {
            // 调用进程输出对象的终端写入提示信息
            processOutput.term.write(tips);
        }

        /**
         * 获取进程的缓存位置
         * @return 进程的缓存位置
         */
        @Override
        public String cacheLocation() {
            return ProcessImpl.this.cacheLocation();
        }

        /**
         * 设置终端大小改变事件处理器
         * @param handler 终端大小改变事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess resizehandler(Handler<Void> handler) {
            // 设置终端大小改变事件处理器
            resizeHandler = handler;
            // 设置终端的大小改变处理器
            tty.resizehandler(resizeHandler);
            return this;
        }

        /**
         * 设置中断事件处理器
         * @param handler 中断事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess interruptHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置中断事件处理器
                interruptHandler = handler;
            }
            return this;
        }

        /**
         * 设置暂停事件处理器
         * @param handler 暂停事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess suspendHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置暂停事件处理器
                suspendHandler = handler;
            }
            return this;
        }

        /**
         * 设置恢复事件处理器
         * @param handler 恢复事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess resumeHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置恢复事件处理器
                resumeHandler = handler;
            }
            return this;
        }

        /**
         * 设置结束事件处理器
         * @param handler 结束事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess endHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置结束事件处理器
                endHandler = handler;
            }
            return this;
        }

        /**
         * 设置后台运行事件处理器
         * @param handler 后台运行事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess backgroundHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置后台运行事件处理器
                backgroundHandler = handler;
            }
            return this;
        }

        /**
         * 设置前台运行事件处理器
         * @param handler 前台运行事件处理器
         * @return 当前命令处理实现对象
         */
        @Override
        public CommandProcess foregroundHandler(Handler<Void> handler) {
            synchronized (ProcessImpl.this) {
                // 设置前台运行事件处理器
                foregroundHandler = handler;
            }
            return this;
        }

        /**
         * 注册建议监听器和类文件转换器
         * @param adviceListener 建议监听器
         * @param transformer 类文件转换器
         */
        @Override
        public void register(AdviceListener adviceListener, ClassFileTransformer transformer) {
            if (adviceListener instanceof ProcessAware) {
                ProcessAware processAware = (ProcessAware) adviceListener;
                // 如果建议监听器实现了ProcessAware接口且进程对象为空，则设置进程对象
                if(processAware.getProcess() == null) {
                    processAware.setProcess(this.process);
                }
            }
            // 设置建议监听器
            this.listener = adviceListener;
            // 注册建议监听器
            AdviceWeaver.reg(listener);

            // 设置类文件转换器
            this.transformer = transformer;
        }

        /**
         * 注销建议监听器和类文件转换器
         */
        @Override
        public void unregister() {
            if (transformer != null) {
                // 如果类文件转换器不为空，则从变压器管理器中移除它
                ArthasBootstrap.getInstance().getTransformerManager().removeTransformer(transformer);
            }

            if (listener instanceof ProcessAware) {
                // 如果建议监听器实现了ProcessAware接口且进程对象相同，则注销建议监听器
                if (this.process.equals(((ProcessAware) listener).getProcess())) {
                    AdviceWeaver.unReg(listener);
                }
            } else {
                // 否则直接注销建议监听器
                AdviceWeaver.unReg(listener);
            }
        }

        /**
         * 恢复进程执行
         */
        @Override
        public void resume() {
//            if (suspendedListener != null) {
//                AdviceWeaver.resume(suspendedListener);
//                suspendedListener = null;
//            }
        }

        /**
         * 暂停进程执行
         */
        @Override
        public void suspend() {
//            if (this.enhanceLock >= 0) {
//                suspendedListener = AdviceWeaver.suspend(enhanceLock);
//            }
        }

        /**
         * 结束命令处理，调用带退出码的结束方法，默认退出码为0
         */
        @Override
        public void end() {
            end(0);
        }

        /**
         * 结束命令处理，调用带退出码和消息的结束方法
         * @param statusCode 退出码
         */
        @Override
        public void end(int statusCode) {
            end(statusCode, null);
        }

        /**
         * 结束命令处理，调用私有终止方法
         * @param statusCode 退出码
         * @param message 结束消息
         */
        @Override
        public void end(int statusCode, String message) {
            // 调用私有终止方法
            terminate(statusCode, null, message);
        }

        /**
         * 判断进程是否在运行
         * @return 如果在运行则返回true，否则返回false
         */
        @Override
        public boolean isRunning() {
            return processStatus == ExecStatus.RUNNING;
        }

        /**
         * 将结果添加到结果分发器中
         * @param result 结果模型
         */
        @Override
        public void appendResult(ResultModel result) {
            if (processStatus != ExecStatus.RUNNING) {
                // 如果进程不处于运行状态，则抛出异常
                throw new IllegalStateException(
                        "Cannot write to standard output when " + status().name().toLowerCase());
            }
            // 调用外部类的追加结果方法
            ProcessImpl.this.appendResult(result);
        }
    }

    /**
     * 进程输出类，用于处理进程的输出信息
     */
    static class ProcessOutput {

        // 标准输出处理器链
        private List<Function<String, String>> stdoutHandlerChain;
        // 统计处理器
        private StatisticsFunction statisticsHandler = null;
        // 刷新处理器链
        private List<Function<String, String>> flushHandlerChain = null;
        // 缓存位置
        private String cacheLocation;
        // 终端对象
        private Tty term;

        /**
         * 构造函数，初始化进程输出对象
         * @param stdoutHandlerChain 标准输出处理器链
         * @param cacheLocation 缓存位置
         * @param term 终端对象
         */
        public ProcessOutput(List<Function<String, String>> stdoutHandlerChain, String cacheLocation, Tty term) {
            // this.stdoutHandlerChain = stdoutHandlerChain;

            int i = 0;
            for (; i < stdoutHandlerChain.size(); i++) {
                if (stdoutHandlerChain.get(i) instanceof StatisticsFunction) {
                    break;
                }
            }
            if (i < stdoutHandlerChain.size()) {
                // 截取标准输出处理器链，直到找到统计处理器
                this.stdoutHandlerChain = stdoutHandlerChain.subList(0, i + 1);
                // 设置统计处理器
                this.statisticsHandler = (StatisticsFunction) stdoutHandlerChain.get(i);
                if (i < stdoutHandlerChain.size() - 1) {
                    // 设置刷新处理器链
                    flushHandlerChain = stdoutHandlerChain.subList(i + 1, stdoutHandlerChain.size());
                }
            } else {
                // 如果没有找到统计处理器，则使用整个标准输出处理器链
                this.stdoutHandlerChain = stdoutHandlerChain;
            }

            // 设置缓存位置
            this.cacheLocation = cacheLocation;
            // 设置终端对象
            this.term = term;
        }

        /**
         * 向标准输出写入数据，经过标准输出处理器链处理
         * @param data 要写入的数据
         */
        private void write(String data) {
            if (stdoutHandlerChain != null) {
                // 遍历标准输出处理器链，依次处理数据
                int size = stdoutHandlerChain.size();
                for (int i = 0; i < size; i++) {
                    Function<String, String> function = stdoutHandlerChain.get(i);
                    data = function.apply(data);
                }
            }
        }

        /**
         * 关闭进程输出对象，处理统计信息和关闭相关处理器
         */
        private void close() {
            if (statisticsHandler != null && flushHandlerChain != null) {
                // 获取统计结果
                String data = statisticsHandler.result();

                // 遍历刷新处理器链，依次处理统计结果
                for (Function<String, String> function : flushHandlerChain) {
                    data = function.apply(data);
                    if (function instanceof StatisticsFunction) {
                        data = ((StatisticsFunction) function).result();
                    }
                }
            }

            if (stdoutHandlerChain != null) {
                // 遍历标准输出处理器链，关闭相关处理器
                for (Function<String, String> function : stdoutHandlerChain) {
                    if (function instanceof CloseFunction) {
                        ((CloseFunction) function).close();
                    }
                }
            }
        }
    }
}