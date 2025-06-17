package com.taobao.arthas.core.shell.command.impl;

import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.util.UserStatUtil;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.Option;
import com.taobao.middleware.cli.annotations.CLIConfigurator;

import java.util.Collections;

/**
 * 注解命令的实现类
 * 基于注解自动生成命令行接口(CLI)定义
 * 实现命令的解析、执行和补全功能
 *
 * @author beiwei30 on 10/11/2016.
 */
public class AnnotatedCommandImpl extends Command {

    private CLI cli;                         // CLI定义对象
    private Class<? extends AnnotatedCommand> clazz; // 命令类的Class对象
    private Handler<CommandProcess> processHandler = new ProcessHandler(); // 命令处理处理器

    /**
     * 构造函数
     *
     * @param clazz 注解命令类的Class对象
     */
    public AnnotatedCommandImpl(Class<? extends AnnotatedCommand> clazz) {
        this.clazz = clazz;
        // 通过注解配置生成CLI定义
        cli = CLIConfigurator.define(clazz, true);
        // 添加帮助选项
        cli.addOption(new Option().setArgName("help").setFlag(true).setShortName("h").setLongName("help")
                .setDescription("this help").setHelp(true));
    }

    /**
     * 检查命令类是否重写了name方法
     *
     * @param clazz 命令类的Class对象
     * @return 是否重写了name方法
     */
    private boolean shouldOverridesName(Class<? extends AnnotatedCommand> clazz) {
        try {
            clazz.getDeclaredMethod("name");
            return true;
        } catch (NoSuchMethodException ignore) {
            return false;
        }
    }

    /**
     * 检查命令类是否重写了cli方法
     *
     * @param clazz 命令类的Class对象
     * @return 是否重写了cli方法
     */
    private boolean shouldOverrideCli(Class<? extends AnnotatedCommand> clazz) {
        try {
            clazz.getDeclaredMethod("cli");
            return true;
        } catch (NoSuchMethodException ignore) {
            return false;
        }
    }

    /**
     * 获取命令名称
     * 优先使用命令类重写的name方法，否则使用CLI定义的名称
     */
    @Override
    public String name() {
        if (shouldOverridesName(clazz)) {
            try {
                return clazz.newInstance().name();
            } catch (Exception ignore) {
                // 实例化或调用失败时，使用CLI定义的名称
            }
        }
        return cli.getName();
    }

    /**
     * 获取CLI定义
     * 优先使用命令类重写的cli方法，否则使用自动生成的CLI定义
     */
    @Override
    public CLI cli() {
        if (shouldOverrideCli(clazz)) {
            try {
                return clazz.newInstance().cli();
            } catch (Exception ignore) {
                // 实例化或调用失败时，使用自动生成的CLI定义
            }
        }
        return cli;
    }

    /**
     * 处理命令执行
     *
     * @param process 命令处理上下文
     */
    private void process(CommandProcess process) {
        AnnotatedCommand instance;
        try {
            // 实例化命令对象
            instance = clazz.newInstance();
        } catch (Exception e) {
            // 实例化失败时结束命令处理
            process.end();
            return;
        }
        // 注入命令行参数到命令对象
        CLIConfigurator.inject(process.commandLine(), instance);
        // 执行命令处理逻辑
        instance.process(process);
        // 统计命令使用情况
        UserStatUtil.arthasUsageSuccess(name(), process.args());
    }

    /**
     * 获取命令处理处理器
     */
    @Override
    public Handler<CommandProcess> processHandler() {
        return processHandler;
    }

    /**
     * 命令补全功能实现
     *
     * @param completion 补全上下文
     */
    @Override
    public void complete(final Completion completion) {
        final AnnotatedCommand instance;
        try {
            // 实例化命令对象
            instance = clazz.newInstance();
        } catch (Exception e) {
            // 实例化失败时使用默认补全逻辑
            super.complete(completion);
            return;
        }

        try {
            // 调用命令对象的补全方法
            instance.complete(completion);
        } catch (Throwable t) {
            // 补全过程中出错时返回空补全结果
            completion.complete(Collections.<String>emptyList());
        }
    }

    /**
     * 命令处理处理器内部类
     */
    private class ProcessHandler implements Handler<CommandProcess> {
        @Override
        public void handle(CommandProcess process) {
            // 执行命令处理逻辑
            process(process);
        }
    }
}