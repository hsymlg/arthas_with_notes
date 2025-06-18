package com.taobao.arthas.core.shell.term.impl;

import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.util.StringUtils;

import java.util.LinkedList;
import java.util.List;

/**
 * 命令补全适配器，将Arthas的Completion接口适配到termd库的Completion接口
 * @author beiwei30 on 23/11/2016.
 */
class CompletionAdaptor implements Completion {
    // 会话对象，用于获取补全相关的上下文信息
    private final Session session;
    // 原始命令行内容
    private final String line;
    // 解析后的命令令牌列表
    private final List<CliToken> tokens;
    // termd库的补全对象，用于实际执行补全操作
    private final io.termd.core.readline.Completion completion;

    /**
     * 构造函数，初始化适配器
     * @param line 原始命令行内容
     * @param tokens 解析后的命令令牌列表
     * @param completion termd库的补全对象
     * @param session 会话对象
     */
    public CompletionAdaptor(String line, List<CliToken> tokens, io.termd.core.readline.Completion completion,
                             Session session) {
        this.line = line;
        this.tokens = tokens;
        this.completion = completion;
        this.session = session;
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
     * 获取原始命令行内容
     * @return 原始命令行字符串
     */
    @Override
    public String rawLine() {
        return line;
    }

    /**
     * 获取解析后的命令令牌列表
     * @return 命令令牌列表
     */
    @Override
    public List<CliToken> lineTokens() {
        return tokens;
    }

    /**
     * 以候选列表完成补全操作
     * @param candidates 补全候选列表
     */
    @Override
    public void complete(List<String> candidates) {
        // 获取最后一个令牌的值，处理空令牌情况
        String lastToken = tokens.isEmpty() ? null : tokens.get(tokens.size() - 1).value();
        if (StringUtils.isBlank(lastToken)) {
            lastToken = "";
        }
        // 当有多个候选时，查找最长公共前缀
        if (candidates.size() > 1) {
            String commonPrefix = CompletionUtils.findLongestCommonPrefix(candidates);
            if (commonPrefix.length() > 0) {
                // 只有当公共前缀比最后一个令牌长时才进行补全
                if (!commonPrefix.equals(lastToken) && commonPrefix.length() > lastToken.length()) {
                    String strToComplete = commonPrefix.substring(lastToken.length());
                    // 使用termd的补全接口完成补全，标记为非终结符（可继续补全）
                    completion.complete(io.termd.core.util.Helper.toCodePoints(strToComplete), false);
                    return;
                }
            }
        }
        // 处理候选列表
        if (candidates.size() > 0) {
            List<int[]> suggestions = new LinkedList<int[]>();
            // 将候选字符串转换为码点数组
            for (String candidate : candidates) {
                suggestions.add(io.termd.core.util.Helper.toCodePoints(candidate));
            }
            // 提供补全建议
            completion.suggest(suggestions);
        } else {
            // 没有候选时结束补全
            completion.end();
        }
    }

    /**
     * 以指定值完成补全操作
     * @param value 补全值
     * @param terminal 是否为终结符（true表示不可继续补全）
     */
    @Override
    public void complete(String value, boolean terminal) {
        // 将补全值转换为码点数组，并调用termd的补全接口
        completion.complete(io.termd.core.util.Helper.toCodePoints(value), terminal);
    }
}