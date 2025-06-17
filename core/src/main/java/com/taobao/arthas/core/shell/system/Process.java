package com.taobao.arthas.core.shell.system;

import java.util.Date;

import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.shell.term.Tty;

/**
 * A process managed by the shell.
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public interface Process {
    // 获取当前进程状态
    ExecStatus status();

    // 获取进程退出码，仅当状态为 TERMINATED 时有效
    Integer exitCode();

    // 设置进程的 TTY
    Process setTty(Tty tty);

    // 获取进程的 TTY
    Tty getTty();

    // 设置进程的会话
    Process setSession(Session session);

    // 获取进程的会话
    Session getSession();

    // 设置进程终止时的处理程序
    Process terminatedHandler(Handler<Integer> handler);

    // 运行进程
    void run();

    // 运行进程，指定是否为前台进程
    void run(boolean foreground);

    // 尝试中断进程
    boolean interrupt();

    // 尝试中断进程，并在中断回调后调用完成处理程序
    boolean interrupt(Handler<Void> completionHandler);

    // 恢复进程
    void resume();

    // 恢复进程，指定是否为前台进程
    void resume(boolean foreground);

    // 恢复进程，并在恢复回调后调用完成处理程序
    void resume(Handler<Void> completionHandler);

    // 恢复进程，指定是否为前台进程，并在恢复回调后调用完成处理程序
    void resume(boolean foreground, Handler<Void> completionHandler);

    // 暂停进程
    void suspend();

    // 暂停进程，并在暂停回调后调用完成处理程序
    void suspend(Handler<Void> completionHandler);

    // 终止进程
    void terminate();

    // 终止进程，并在结束回调后调用完成处理程序
    void terminate(Handler<Void> completionHandler);

    // 将进程设置为后台运行
    void toBackground();

    // 将进程设置为后台运行，并在后台回调后调用完成处理程序
    void toBackground(Handler<Void> completionHandler);

    // 将进程设置为前台运行
    void toForeground();

    // 将进程设置为前台运行，并在前台回调后调用完成处理程序
    void toForeground(Handler<Void> completionHandler);

    // 获取进程的执行次数
    int times();

    // 获取进程的启动时间
    Date startTime();

    // 获取进程的缓存文件位置
    String cacheLocation();

    // 设置进程所属的作业 ID
    void setJobId(int jobId);
}