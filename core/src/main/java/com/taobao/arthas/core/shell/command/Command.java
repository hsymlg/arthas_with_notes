package com.taobao.arthas.core.shell.command;

import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.middleware.cli.CLI;

import java.util.Collections;
import java.util.List;

/**
 * 该抽象类定义了一个命令的基本结构和行为。
 */
public abstract class Command {

    /**
     * 从一个使用 CLI 注解的 Java 类创建一个命令对象。
     *
     * @param clazz 命令类的 Class 对象，该类必须继承自 AnnotatedCommand
     * @return 一个新的命令对象，由 AnnotatedCommandImpl 类实例化得到
     */
    public static Command create(final Class<? extends AnnotatedCommand> clazz) {
        return new AnnotatedCommandImpl(clazz);
    }

    /**
     * 获取命令的名称。
     * 默认实现返回 null，具体的命令类可以重写此方法以提供实际的命令名称。
     *
     * @return 命令的名称，如果未指定则返回 null
     */
    public String name() {
        return null;
    }

    /**
     * 获取命令的命令行接口（CLI）对象。
     * 默认实现返回 null，具体的命令类可以重写此方法以提供实际的 CLI 对象。
     *
     * @return 命令的 CLI 对象，如果未指定则返回 null
     */
    public CLI cli() {
        return null;
    }

    /**
     * 创建一个新的命令处理程序，用于处理命令的执行过程。
     * 这是一个抽象方法，具体的命令类必须实现此方法。
     *
     * @return 一个处理命令过程的处理器对象，该处理器接受 CommandProcess 类型的参数
     */
    public abstract Handler<CommandProcess> processHandler();

    /**
     * 执行命令的自动补全操作。
     * 当命令完成补全后，应该调用 Completion 对象的 complete 方法来通知补全完成。
     * 默认实现返回一个空的补全列表。
     *
     * @param completion 补全对象，用于处理命令补全的相关操作
     */
    public void complete(Completion completion) {
        // 调用 Completion 对象的 complete 方法，传入一个空的字符串列表作为补全结果
        completion.complete(Collections.<String>emptyList());
    }
}