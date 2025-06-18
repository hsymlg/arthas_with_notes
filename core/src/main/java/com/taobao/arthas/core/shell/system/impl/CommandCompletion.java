package com.taobao.arthas.core.shell.system.impl;

import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.session.Session;

import java.util.List;

/**
 * 命令补全适配器类，用于包装原始补全对象并修改命令行令牌
 * @author beiwei30 on 23/11/2016.
 */
class CommandCompletion implements Completion {
    // 包装的原始补全对象
    private final Completion completion;
    // 命令行原始内容
    private final String line;
    // 新的命令行令牌列表（可能已修改）
    private final List<CliToken> newTokens;

    /**
     * 构造函数，初始化命令补全适配器
     * @param completion 原始补全对象
     * @param line 命令行原始内容
     * @param newTokens 新的命令行令牌列表
     */
    public CommandCompletion(Completion completion, String line, List<CliToken> newTokens) {
        this.completion = completion;
        this.line = line;
        this.newTokens = newTokens;
    }

    /**
     * 获取会话对象，委托给原始补全对象实现
     * @return 会话对象
     */
    @Override
    public Session session() {
        return completion.session();
    }

    /**
     * 获取原始命令行内容
     * @return 命令行原始字符串
     */
    @Override
    public String rawLine() {
        return line;
    }

    /**
     * 获取修改后的命令行令牌列表
     * @return 新的命令行令牌列表
     */
    @Override
    public List<CliToken> lineTokens() {
        return newTokens;
    }

    /**
     * 以候选列表完成补全操作，委托给原始补全对象实现
     * @param candidates 补全候选列表
     */
    @Override
    public void complete(List<String> candidates) {
        completion.complete(candidates);
    }

    /**
     * 以指定值完成补全操作，委托给原始补全对象实现
     * @param value 补全值
     * @param terminal 是否为终结符（true表示不可继续补全）
     */
    @Override
    public void complete(String value, boolean terminal) {
        completion.complete(value, terminal);
    }
}