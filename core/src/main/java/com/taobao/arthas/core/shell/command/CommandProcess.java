package com.taobao.arthas.core.shell.command;

// 引入 AdviceListener 类，用于监听方法调用的通知
import com.taobao.arthas.core.advisor.AdviceListener;
// 引入 ResultModel 类，用于表示命令执行结果的模型
import com.taobao.arthas.core.command.model.ResultModel;
// 引入 CliToken 类，用于表示命令行的令牌（如参数、选项等）
import com.taobao.arthas.core.shell.cli.CliToken;
// 引入 Handler 类，用于处理事件的通用接口
import com.taobao.arthas.core.shell.handlers.Handler;
// 引入 Session 类，用于表示 shell 会话
import com.taobao.arthas.core.shell.session.Session;
// 引入 Tty 类，用于表示终端的接口
import com.taobao.arthas.core.shell.term.Tty;
// 引入 CommandLine 类，用于表示解析后的命令行对象
import com.taobao.middleware.cli.CommandLine;

import java.lang.instrument.ClassFileTransformer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The command process provides interaction with the process of the command.
 * 该接口提供了与命令执行过程进行交互的功能，允许对命令的输入、输出、状态等进行控制和监听
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public interface CommandProcess extends Tty {
    /**
     * @return the unparsed arguments tokens
     * 获取未解析的命令行参数令牌列表，这些令牌包含了原始的命令行输入信息
     */
    List<CliToken> argsTokens();

    /**
     * @return the actual string arguments of the command
     * 获取命令的实际字符串参数列表，是经过处理后的参数信息
     */
    List<String> args();

    /**
     * @return the command line object or null
     * 获取解析后的命令行对象，如果没有进行解析则返回 null
     */
    CommandLine commandLine();

    /**
     * @return the shell session
     * 获取当前命令所属的 shell 会话对象，可通过会话对象获取更多上下文信息
     */
    Session session();

    /**
     * @return true if the command is running in foreground
     * 判断命令是否在前台运行，如果是则返回 true，否则返回 false
     */
    boolean isForeground();

    /**
     * 设置标准输入的处理程序，当有标准输入时会调用该处理程序进行处理
     * @param handler 处理标准输入的处理程序
     * @return 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess stdinHandler(Handler<String> handler);

    /**
     * Set an interrupt handler, this handler is called when the command is interrupted, for instance user
     * press <code>Ctrl-C</code>.
     * 设置命令中断的处理程序，当用户按下 Ctrl-C 等中断命令执行的操作时，会调用该处理程序
     *
     * @param handler the interrupt handler 中断处理程序
     * @return this command 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess interruptHandler(Handler<Void> handler);

    /**
     * Set a suspend handler, this handler is called when the command is suspended, for instance user
     * press <code>Ctrl-Z</code>.
     * 设置命令暂停的处理程序，当用户按下 Ctrl-Z 等暂停命令执行的操作时，会调用该处理程序
     *
     * @param handler the interrupt handler 暂停处理程序
     * @return this command 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess suspendHandler(Handler<Void> handler);

    /**
     * Set a resume handler, this handler is called when the command is resumed, for instance user
     * types <code>bg</code> or <code>fg</code> to resume the command.
     * 设置命令恢复的处理程序，当用户输入 bg 或 fg 等恢复命令执行的操作时，会调用该处理程序
     *
     * @param handler the interrupt handler 恢复处理程序
     * @return this command 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess resumeHandler(Handler<Void> handler);

    /**
     * Set an end handler, this handler is called when the command is ended, for instance the command is running
     * and the shell closes.
     * 设置命令结束的处理程序，当命令执行结束（如正常结束、异常结束或 shell 关闭等情况）时，会调用该处理程序
     *
     * @param handler the end handler 结束处理程序
     * @return a reference to this, so the API can be used fluently 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess endHandler(Handler<Void> handler);

    /**
     * Write some text to the standard output.
     * 向标准输出写入文本信息
     *
     * @param data the text 要写入的文本内容
     * @return a reference to this, so the API can be used fluently 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess write(String data);

    /**
     * Set a background handler, this handler is called when the command is running and put to background.
     * 设置命令放入后台运行的处理程序，当命令从前台切换到后台运行时，会调用该处理程序
     *
     * @param handler the background handler 后台处理程序
     * @return this command 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess backgroundHandler(Handler<Void> handler);

    /**
     * Set a foreground handler, this handler is called when the command is running and put to foreground.
     * 设置命令放入前台运行的处理程序，当命令从后台切换到前台运行时，会调用该处理程序
     *
     * @param handler the foreground handler 前台处理程序
     * @return this command 返回当前 CommandProcess 对象，方便链式调用
     */
    CommandProcess foregroundHandler(Handler<Void> handler);

    /**
     * 重写 Tty 接口的 resizehandler 方法，设置终端大小改变的处理程序
     * @param handler 终端大小改变的处理程序
     * @return 返回当前 CommandProcess 对象，方便链式调用
     */
    @Override
    CommandProcess resizehandler(Handler<Void> handler);

    /**
     * End the process with the exit status {@literal 0}
     * 以退出状态码 0 结束命令执行，表示命令正常结束
     */
    void end();

    /**
     * End the process.
     * 以指定的退出状态码结束命令执行
     *
     * @param status the exit status. 退出状态码
     */
    void end(int status);

    /**
     * End the process.
     * 以指定的退出状态码和消息结束命令执行
     *
     * @param status the exit status. 退出状态码
     * @param message 结束消息，可用于提示用户命令结束的原因等信息
     */
    void end(int status, String message);

    /**
     * Register listener
     * 注册一个 AdviceListener 和 ClassFileTransformer，用于监听方法调用和转换类文件
     *
     * @param listener 要注册的 AdviceListener
     * @param transformer 要注册的 ClassFileTransformer
     */
    void register(AdviceListener listener, ClassFileTransformer transformer);

    /**
     * Unregister listener
     * 取消注册之前注册的 AdviceListener 和 ClassFileTransformer
     */
    void unregister();

    /**
     * Execution times
     * 获取命令的执行次数，使用 AtomicInteger 保证线程安全
     *
     * @return execution times 命令执行的次数
     */
    AtomicInteger times();

    /**
     * Resume process
     * 恢复命令的执行，通常在命令被暂停后调用
     */
    void resume();

    /**
     * Suspend process
     * 暂停命令的执行
     */
    void suspend();

    /**
     * echo tips
     * 输出提示信息
     *
     * @param tips process tips 提示信息内容
     */
    void echoTips(String tips);

    /**
     * Get cache file location
     * 获取缓存文件的存储位置
     *
     * @return 缓存文件的存储位置字符串
     */
    String cacheLocation();

    /**
     * Whether the process is running
     * 判断命令是否正在运行
     * @return 如果命令正在运行返回 true，否则返回 false
     */
    boolean isRunning();

    /**
     * Append the phased result to queue
     * 将阶段性的命令执行结果添加到结果队列中
     * @param result a phased result of the command 阶段性的命令执行结果对象
     */
    void appendResult(ResultModel result);

}