package com.taobao.arthas.core.shell.command;

// 引入命令行补全相关的类
import com.taobao.arthas.core.shell.cli.Completion;
// 引入命令行补全工具类
import com.taobao.arthas.core.shell.cli.CompletionUtils;
// 引入命令行接口定义类
import com.taobao.middleware.cli.CLI;

import java.util.List;

/**
 * The base command class that Java annotated command should extend.
 * 这是一个抽象类，是所有使用Java注解方式定义的命令类的基类，其他具体的命令类需要继承这个类
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class AnnotatedCommand {

    /**
     * @return the command name
     * 获取命令的名称，默认返回 null，具体的命令类可以重写这个方法来返回实际的命令名称
     */
    public String name() {
        return null;
    }

    /**
     * @return the command line interface, can be null
     * 获取命令的命令行接口（CLI）对象，该对象可用于定义命令的参数、选项等信息，默认返回 null，具体命令类可重写
     */
    public CLI cli() {
        return null;
    }

    /**
     * Process the command, when the command is done processing it should call the {@link CommandProcess#end()} method.
     * 处理命令的核心方法，具体的命令逻辑应该在子类中实现这个方法。
     * 当命令处理完成后，需要调用 CommandProcess 对象的 end() 方法来结束命令处理流程
     *
     * @param process the command process
     *                命令处理过程对象，用于与命令行环境进行交互，例如输出信息、获取参数等
     */
    public abstract void process(CommandProcess process);

    /**
     * Perform command completion, when the command is done completing it should call {@link Completion#complete(List)}
     * or {@link Completion#complete(String, boolean)} )} method to signal completion is done.
     * 执行命令补全操作，当命令补全完成后，需要调用 Completion 对象的 complete 相关方法来通知补全结束
     *
     * @param completion the completion object
     *                   命令补全对象，用于获取当前命令行的状态信息，并提供补全结果
     */
    public void complete(Completion completion) {
        // 调用 CompletionUtils 工具类的 complete 方法来完成补全操作，传入当前命令类的 Class 对象
        CompletionUtils.complete(completion, this.getClass());
    }

}