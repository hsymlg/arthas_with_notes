package com.taobao.arthas.core.shell.cli;

import com.taobao.arthas.core.shell.session.Session;

import java.util.List;

/**
 * 命令补全接口，用于处理命令行输入时的自动补全功能
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public interface Completion {

    /**
     * 获取当前shell会话对象
     * @return 当前会话对象，可用于获取会话相关数据（如文件补全时的当前路径）
     */
    Session session();

    /**
     * 获取正在补全的原始命令行内容（未进行任何字符转义）
     * @return 原始命令行字符串
     */
    String rawLine();

    /**
     * 获取解析后的命令行令牌列表
     * @return 命令行令牌列表，每个令牌表示命令行中的一个部分（如参数、选项等）
     */
    List<CliToken> lineTokens();

    /**
     * 以候选列表结束补全操作，shell会在控制台显示这些候选值
     * @param candidates 补全候选值列表
     */
    void complete(List<String> candidates);

    /**
     * 以指定值结束补全操作，该值将被插入到命令行中
     * @param value 用于补全的具体值
     * @param terminal 标记该值是否为终结符（true表示不可继续补全，false表示可继续补全）
     */
    void complete(String value, boolean terminal);
}