package com.taobao.arthas.core.shell.impl;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.alibaba.arthas.tunnel.client.TunnelClient;
import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.Shell;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.ShellServerOptions;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.arthas.core.shell.future.Future;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.handlers.server.SessionClosedHandler;
import com.taobao.arthas.core.shell.handlers.server.SessionsClosedHandler;
import com.taobao.arthas.core.shell.handlers.server.TermServerListenHandler;
import com.taobao.arthas.core.shell.handlers.server.TermServerTermHandler;
import com.taobao.arthas.core.shell.system.Job;
import com.taobao.arthas.core.shell.system.impl.GlobalJobControllerImpl;
import com.taobao.arthas.core.shell.system.impl.InternalCommandManager;
import com.taobao.arthas.core.shell.system.impl.JobControllerImpl;
import com.taobao.arthas.core.shell.term.Term;
import com.taobao.arthas.core.shell.term.TermServer;
import com.taobao.arthas.core.util.ArthasBanner;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arthas shell服务器的实现类
 * 负责管理终端会话、命令解析、作业控制等核心功能
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ShellServerImpl extends ShellServer {

    private static final Logger logger = LoggerFactory.getLogger(ShellServerImpl.class);

    // 命令解析器列表，支持并发修改
    private final CopyOnWriteArrayList<CommandResolver> resolvers;
    // 命令管理器，处理命令的注册和执行
    private final InternalCommandManager commandManager;
    // 终端服务器列表，支持多终端连接
    private final List<TermServer> termServers;
    // 会话超时时间(毫秒)
    private final long timeoutMillis;
    // 会话清理间隔(毫秒)
    private final long reaperInterval;
    // 欢迎消息
    private String welcomeMessage;
    // Java Instrumentation实例，用于字节码增强
    private Instrumentation instrumentation;
    // 当前进程ID
    private long pid;
    // 服务器关闭状态标记
    private boolean closed = true;
    // 会话映射表，存储所有活动会话
    private final Map<String, ShellImpl> sessions;
    // 所有会话关闭的Future，用于异步通知
    private final Future<Void> sessionsClosed = Future.future();
    // 定时任务执行器，用于会话清理
    private ScheduledExecutorService scheduledExecutorService;
    // 作业控制器，管理所有作业
    private JobControllerImpl jobController = new GlobalJobControllerImpl();

    /**
     * 构造函数，初始化shell服务器
     *
     * @param options 服务器配置选项
     */
    public ShellServerImpl(ShellServerOptions options) {
        // 设置欢迎消息
        this.welcomeMessage = options.getWelcomeMessage();
        // 初始化终端服务器列表
        this.termServers = new ArrayList<TermServer>();
        // 设置会话超时时间
        this.timeoutMillis = options.getSessionTimeout();
        // 初始化会话映射表
        this.sessions = new ConcurrentHashMap<String, ShellImpl>();
        // 设置会话清理间隔
        this.reaperInterval = options.getReaperInterval();
        // 初始化命令解析器列表
        this.resolvers = new CopyOnWriteArrayList<CommandResolver>();
        // 初始化命令管理器
        this.commandManager = new InternalCommandManager(resolvers);
        // 设置Instrumentation实例
        this.instrumentation = options.getInstrumentation();
        // 设置当前进程ID
        this.pid = options.getPid();

        // 注册内置命令解析器，确保帮助信息中列出内置命令
        resolvers.add(new BuiltinCommandResolver());
    }

    /**
     * 注册命令解析器
     *
     * @param resolver 命令解析器
     * @return ShellServer实例，支持链式调用
     */
    @Override
    public synchronized ShellServer registerCommandResolver(CommandResolver resolver) {
        // 将解析器添加到列表头部，确保高优先级
        resolvers.add(0, resolver);
        return this;
    }

    /**
     * 注册终端服务器
     *
     * @param termServer 终端服务器
     * @return ShellServer实例，支持链式调用
     */
    @Override
    public synchronized ShellServer registerTermServer(TermServer termServer) {
        // 添加终端服务器到列表
        termServers.add(termServer);
        return this;
    }

    /**
     * 处理新的终端连接
     *
     * @param term 终端实例
     */
    public void handleTerm(Term term) {
        synchronized (this) {
            // 服务器已关闭时关闭终端
            if (closed) {
                term.close();
                return;
            }
        }

        // 创建shell会话
        ShellImpl session = createShell(term);
        // 尝试更新欢迎消息（包含隧道ID等信息）
        tryUpdateWelcomeMessage();
        // 设置欢迎消息
        session.setWelcome(welcomeMessage);
        // 设置会话关闭处理器
        session.closedFuture.setHandler(new SessionClosedHandler(this, session));
        // 初始化会话
        session.init();
        // 将会话添加到映射表（在init之后，确保连接关闭处理器已设置）
        sessions.put(session.id, session);
        // 开始读取命令行
        session.readline();
    }

    /**
     * 尝试更新欢迎消息，包含隧道客户端ID等信息
     */
    private void tryUpdateWelcomeMessage() {
        // 获取隧道客户端实例
        TunnelClient tunnelClient = ArthasBootstrap.getInstance().getTunnelClient();
        if (tunnelClient != null) {
            // 获取隧道客户端ID
            String id = tunnelClient.getId();
            if (id != null) {
                // 创建欢迎消息信息映射
                Map<String, String> welcomeInfos = new HashMap<String, String>();
                welcomeInfos.put("id", id);
                // 更新欢迎消息
                this.welcomeMessage = ArthasBanner.welcome(welcomeInfos);
            }
        }
    }

    /**
     * 启动服务器监听
     *
     * @param listenHandler 监听处理回调
     * @return ShellServer实例，支持链式调用
     */
    @Override
    public ShellServer listen(final Handler<Future<Void>> listenHandler) {
        // 获取要启动的终端服务器列表
        final List<TermServer> toStart;
        synchronized (this) {
            // 检查服务器状态，已启动时抛出异常
            if (!closed) {
                throw new IllegalStateException("Server listening");
            }
            toStart = termServers;
        }
        // 计数器，用于跟踪启动进度
        final AtomicInteger count = new AtomicInteger(toStart.size());
        if (count.get() == 0) {
            // 没有终端服务器时直接设置为已启动状态
            setClosed(false);
            listenHandler.handle(Future.<Void>succeededFuture());
            return this;
        }
        // 创建终端服务器监听处理器
        Handler<Future<TermServer>> handler = new TermServerListenHandler(this, listenHandler, toStart);
        // 为每个终端服务器设置监听
        for (TermServer termServer : toStart) {
            termServer.termHandler(new TermServerTermHandler(this));
            termServer.listen(handler);
        }
        return this;
    }

    /**
     * 清理过期会话
     */
    private void evictSessions() {
        long now = System.currentTimeMillis();
        // 收集要关闭的会话
        Set<ShellImpl> toClose = new HashSet<ShellImpl>();
        for (ShellImpl session : sessions.values()) {
            // 会话超时且没有运行中的作业时关闭会话
            if (now - session.lastAccessedTime() > timeoutMillis && session.jobs().size() == 0) {
                toClose.add(session);
            }
            logger.debug(session.id + ":" + session.lastAccessedTime());
        }
        // 关闭过期会话
        for (ShellImpl session : toClose) {
            long timeOutInMinutes = timeoutMillis / 1000 / 60;
            String reason = "session is inactive for " + timeOutInMinutes + " min(s).";
            session.close(reason);
        }
    }

    /**
     * 设置会话清理定时器
     */
    public synchronized void setTimer() {
        if (!closed && reaperInterval > 0) {
            // 创建定时任务执行器
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    // 创建守护线程
                    final Thread t = new Thread(r, "arthas-shell-server");
                    t.setDaemon(true);
                    return t;
                }
            });
            // 定期执行会话清理任务
            scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    evictSessions();
                }
            }, 0, reaperInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 设置服务器关闭状态
     *
     * @param closed 关闭状态
     */
    public synchronized void setClosed(boolean closed) {
        this.closed = closed;
    }

    /**
     * 移除会话
     *
     * @param shell 要移除的会话
     */
    public void removeSession(ShellImpl shell) {
        boolean completeSessionClosed;

        // 终止前台作业
        Job job = shell.getForegroundJob();
        if (job != null) {
            job.terminate();
            logger.info("Session {} closed, so terminate foreground job, id: {}, line: {}",
                    shell.session().getSessionId(), job.id(), job.line());
        }

        synchronized (ShellServerImpl.this) {
            // 从映射表中移除会话
            sessions.remove(shell.id);
            shell.close("network error");
            // 检查是否所有会话已关闭且服务器已关闭
            completeSessionClosed = sessions.isEmpty() && closed;
        }
        if (completeSessionClosed) {
            // 完成所有会话关闭的Future
            sessionsClosed.complete();
        }
    }

    /**
     * 创建shell会话（无终端）
     */
    @Override
    public synchronized Shell createShell() {
        return createShell(null);
    }

    /**
     * 创建shell会话（带终端）
     *
     * @param term 终端实例
     * @return ShellImpl实例
     */
    @Override
    public synchronized ShellImpl createShell(Term term) {
        if (closed) {
            throw new IllegalStateException("Closed");
        }
        // 创建shell会话实例
        return new ShellImpl(this, term, commandManager, instrumentation, pid, jobController);
    }

    /**
     * 关闭服务器
     *
     * @param completionHandler 完成处理回调
     */
    @Override
    public void close(final Handler<Future<Void>> completionHandler) {
        List<TermServer> toStop;
        List<ShellImpl> toClose;
        synchronized (this) {
            if (closed) {
                // 已关闭时返回空列表
                toStop = Collections.emptyList();
                toClose = Collections.emptyList();
            } else {
                // 设置服务器为关闭状态
                setClosed(true);
                if (scheduledExecutorService != null) {
                    // 关闭定时任务执行器
                    scheduledExecutorService.shutdownNow();
                }
                // 获取要停止的终端服务器和要关闭的会话
                toStop = termServers;
                toClose = new ArrayList<ShellImpl>(sessions.values());
                if (toClose.isEmpty()) {
                    // 没有会话时直接完成
                    sessionsClosed.complete();
                }
            }
        }
        if (toStop.isEmpty() && toClose.isEmpty()) {
            // 无操作时直接返回成功Future
            completionHandler.handle(Future.<Void>succeededFuture());
        } else {
            // 计数器，用于跟踪关闭进度
            final AtomicInteger count = new AtomicInteger(1 + toClose.size());
            // 创建会话关闭处理器
            Handler<Future<Void>> handler = new SessionsClosedHandler(count, completionHandler);

            // 关闭所有会话
            for (ShellImpl shell : toClose) {
                shell.close("server is going to shutdown.");
            }

            // 关闭所有终端服务器
            for (TermServer termServer : toStop) {
                termServer.close(handler);
            }
            // 关闭作业控制器
            jobController.close();
            // 设置会话关闭Future的处理器
            sessionsClosed.setHandler(handler);
        }
    }

    /**
     * 获取作业控制器
     *
     * @return 作业控制器实例
     */
    public JobControllerImpl getJobController() {
        return jobController;
    }

    /**
     * 获取命令管理器
     *
     * @return 命令管理器实例
     */
    public InternalCommandManager getCommandManager() {
        return commandManager;
    }
}