package com.taobao.arthas.core.shell.cli;

import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.shell.term.Tty;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.usage.StyledUsageFormatter;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.Option;
import com.taobao.middleware.cli.annotations.CLIConfigurator;
import io.termd.core.util.Helper;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 命令行自动补全工具类，提供各种补全逻辑的实现
 * @author beiwei30 on 09/11/2016.
 */
public class CompletionUtils {

    /**
     * 查找集合中字符串的最长公共前缀
     * @param values 字符串集合
     * @return 最长公共前缀字符串
     */
    public static String findLongestCommonPrefix(Collection<String> values) {
        List<int[]> entries = new LinkedList<int[]>();
        // 将每个字符串转换为码点数组
        for (String value : values) {
            int[] entry = Helper.toCodePoints(value);
            entries.add(entry);
        }
        // 调用termd库的方法查找最长公共前缀并转换回字符串
        return Helper.fromCodePoints(io.termd.core.readline.Completion.findLongestCommonPrefix(entries));
    }

    /**
     * 根据命令类完成命令的自动补全
     * @param completion 补全上下文
     * @param clazz 命令类
     */
    public static void complete(Completion completion, Class<?> clazz) {
        List<CliToken> tokens = completion.lineTokens();
        // 获取最后一个命令令牌
        CliToken lastToken = tokens.get(tokens.size() - 1);
        // 通过注解配置获取命令行接口定义
        CLI cli = CLIConfigurator.define(clazz);
        List<com.taobao.middleware.cli.Option> options = cli.getOptions();

        // 根据最后一个令牌的内容判断补全类型
        if (lastToken == null || lastToken.isBlank()) {
            // 补全命令用法说明
            completeUsage(completion, cli);
        } else if (lastToken.value().startsWith("--")) {
            // 补全长选项
            completeLongOption(completion, lastToken, options);
        } else if (lastToken.value().startsWith("-")) {
            // 补全短选项
            completeShortOption(completion, lastToken, options);
        } else {
            // 无匹配补全规则
            completion.complete(Collections.<String>emptyList());
        }
    }

    /**
     * 从搜索范围内完成自动补全
     * @param completion 补全上下文
     * @param searchScope 搜索范围集合
     * @return 是否完成补全
     */
    public static boolean complete(Completion completion, Collection<String> searchScope) {
        List<CliToken> tokens = completion.lineTokens();
        // 获取最后一个令牌的值
        String lastToken = tokens.get(tokens.size() - 1).value();
        List<String> candidates = new ArrayList<String>();

        // 处理空令牌情况
        if (StringUtils.isBlank(lastToken)) {
            lastToken = "";
        }

        // 收集匹配的候选补全项
        for (String name : searchScope) {
            if (name.startsWith(lastToken)) {
                candidates.add(name);
            }
        }

        // 根据候选数量决定补全方式
        if (candidates.size() == 1) {
            // 唯一候选，直接补全
            completion.complete(candidates.get(0).substring(lastToken.length()), true);
            return true;
        } else {
            // 多个候选，显示候选列表
            completion.complete(candidates);
            return true;
        }
    }

    /**
     * 判断令牌是否为目录结尾
     * @param token 令牌值
     * @return 是否为目录结尾
     */
    private static boolean isEndOfDirectory(String token) {
        return !StringUtils.isBlank(token) && (token.endsWith(File.separator) || token.endsWith("/"));
    }

    /**
     * 完成文件路径的自动补全
     * @param completion 补全上下文
     * @return 是否完成补全
     */
    public static boolean completeFilePath(Completion completion) {
        List<CliToken> tokens = completion.lineTokens();
        String token = tokens.get(tokens.size() - 1).value();

        // 忽略以-开头的令牌（可能是命令选项）
        if (token.startsWith("-") || StringUtils.isBlank(token)) {
            return false;
        }

        File dir = null;
        String partName = "";

        // 处理不同的令牌情况，确定补全目录和部分文件名
        if (StringUtils.isBlank(token)) {
            // 空令牌，使用当前目录
            dir = new File("").getAbsoluteFile();
            token = "";
        } else if (isEndOfDirectory(token)) {
            // 目录结尾，使用该目录
            dir = new File(token);
        } else {
            // 普通文件路径，获取父目录和部分文件名
            File parent = new File(token).getAbsoluteFile().getParentFile();
            if (parent != null && parent.exists()) {
                dir = parent;
                partName = new File(token).getName();
            }
        }

        File tokenFile = new File(token);
        String tokenFileName = null;

        // 处理目录结尾情况
        if (isEndOfDirectory(token)) {
            tokenFileName = "";
        } else {
            tokenFileName = tokenFile.getName();
        }

        // 目录不存在时无法补全
        if (dir == null) {
            return false;
        }

        // 获取目录下的文件列表
        File[] listFiles = dir.listFiles();

        ArrayList<String> names = new ArrayList<>();
        if (listFiles != null) {
            // 收集匹配的文件和目录名
            for (File child : listFiles) {
                if (child.getName().startsWith(partName)) {
                    if (child.isDirectory()) {
                        names.add(child.getName() + "/");
                    } else {
                        names.add(child.getName());
                    }
                }
            }
        }

        // 处理唯一候选情况
        if (names.size() == 1 && isEndOfDirectory(names.get(0))) {
            String name = names.get(0);
            // 补全并标记为非终结符（可继续补全）
            completion.complete(name.substring(tokenFileName.length()), false);
            return true;
        }

        // 构建带前缀的候选列表
        String prefix = null;
        if (isEndOfDirectory(token)) {
            prefix = token;
        } else {
            prefix = token.substring(0, token.length() - new File(token).getName().length());
        }

        ArrayList<String> namesWithPrefix = new ArrayList<>();
        for (String name : names) {
            namesWithPrefix.add(prefix + name);
        }

        // 调用通用补全方法
        CompletionUtils.complete(completion, namesWithPrefix);
        return true;
    }

    /**
     * 完成类名的自动补全
     * @param completion 补全上下文
     * @return 是否完成补全
     */
    public static boolean completeClassName(Completion completion) {
        List<CliToken> tokens = completion.lineTokens();
        String lastToken = tokens.get(tokens.size() - 1).value();

        // 处理空令牌情况
        if (StringUtils.isBlank(lastToken)) {
            lastToken = "";
        }

        // 忽略以-开头的令牌（可能是命令选项）
        if (lastToken.startsWith("-")) {
            return false;
        }

        // 获取会话中的Instrumentation实例
        Instrumentation instrumentation = completion.session().getInstrumentation();
        // 获取所有已加载的类
        Class<?>[] allLoadedClasses = instrumentation.getAllLoadedClasses();

        Set<String> result = new HashSet<String>();
        // 收集匹配的类名
        for (Class<?> clazz : allLoadedClasses) {
            String name = clazz.getName();
            // 忽略数组类
            if (name.startsWith("[")) {
                continue;
            }
            if (name.startsWith(lastToken)) {
                int index = name.indexOf('.', lastToken.length());
                // 处理包名情况，只补全包名部分
                if (index > 0) {
                    result.add(name.substring(0, index + 1));
                } else {
                    result.add(name);
                }
            }
        }

        // 处理补全结果
        if (result.size() == 1 && result.iterator().next().endsWith(".")) {
            completion.complete(result.iterator().next().substring(lastToken.length()), false);
        } else {
            CompletionUtils.complete(completion, result);
        }
        return true;
    }

    /**
     * 完成方法名的自动补全
     * @param completion 补全上下文
     * @return 是否完成补全
     */
    public static boolean completeMethodName(Completion completion) {
        List<CliToken> tokens = completion.lineTokens();
        String lastToken = completion.lineTokens().get(tokens.size() - 1).value();

        // 处理空令牌情况
        if (StringUtils.isBlank(lastToken)) {
            lastToken = "";
        }

        String className;
        // 确定要补全方法的类名
        if (StringUtils.isBlank(lastToken)) {
            // 令牌格式: { " ", "CLASS_NAME", " " }
            className = tokens.get(tokens.size() - 2).value();
        } else {
            // 令牌格式: { " ", "CLASS_NAME", " ", "PARTIAL_METHOD_NAME" }
            className = tokens.get(tokens.size() - 3).value();
        }

        // 搜索匹配的类
        Set<Class<?>> results = SearchUtils.searchClassOnly(completion.session().getInstrumentation(), className, 2);
        if (results.size() != 1) {
            // 未找到类或找到多个类
            completion.complete(Collections.<String>emptyList());
            return true;
        }

        Class<?> clazz = results.iterator().next();
        List<String> res = new ArrayList<String>();

        // 收集匹配的方法名
        for (Method method : clazz.getDeclaredMethods()) {
            if (StringUtils.isBlank(lastToken)) {
                res.add(method.getName());
            } else if (method.getName().startsWith(lastToken)) {
                res.add(method.getName());
            }
        }
        // 添加构造函数
        res.add("<init>");

        // 处理补全结果
        if (res.size() == 1) {
            completion.complete(res.get(0).substring(lastToken.length()), true);
            return true;
        } else {
            CompletionUtils.complete(completion, res);
            return true;
        }
    }

    /**
     * 推断当前输入对应的参数索引
     * @param completion 补全上下文
     * @return 参数索引，-1表示选项
     */
    public static int detectArgumentIndex(Completion completion) {
        List<CliToken> tokens = completion.lineTokens();
        CliToken lastToken = tokens.get(tokens.size() - 1);

        // 处理选项情况
        if (lastToken.value().startsWith("-") || lastToken.value().startsWith("--")) {
            return -1;
        }

        // 处理空令牌且只有一个令牌的情况
        if (StringUtils.isBlank((lastToken.value())) && tokens.size() == 1) {
            return 1;
        }

        int tokenCount = 0;

        // 统计有效参数数量
        for (CliToken token : tokens) {
            if (StringUtils.isBlank(token.value()) || token.value().startsWith("-") || token.value().startsWith("--")) {
                continue;
            }
            tokenCount++;
        }

        // 处理空令牌且非单个令牌的情况
        if (StringUtils.isBlank((lastToken.value())) && tokens.size() != 1) {
            tokenCount++;
        }
        return tokenCount;
    }

    /**
     * 完成短选项的自动补全
     * @param completion 补全上下文
     * @param lastToken 最后一个令牌
     * @param options 选项列表
     */
    public static void completeShortOption(Completion completion, CliToken lastToken, List<Option> options) {
        // 提取选项前缀（去掉开头的-）
        String prefix = lastToken.value().substring(1);
        List<String> candidates = new ArrayList<String>();
        // 收集匹配的短选项
        for (Option option : options) {
            if (option.getShortName().startsWith(prefix)) {
                candidates.add(option.getShortName());
            }
        }
        // 执行补全
        complete(completion, prefix, candidates);
    }

    /**
     * 完成长选项的自动补全
     * @param completion 补全上下文
     * @param lastToken 最后一个令牌
     * @param options 选项列表
     */
    public static void completeLongOption(Completion completion, CliToken lastToken, List<Option> options) {
        // 提取选项前缀（去掉开头的--）
        String prefix = lastToken.value().substring(2);
        List<String> candidates = new ArrayList<String>();
        // 收集匹配的长选项
        for (Option option : options) {
            if (option.getLongName().startsWith(prefix)) {
                candidates.add(option.getLongName());
            }
        }
        // 执行补全
        complete(completion, prefix, candidates);
    }

    /**
     * 完成命令用法的自动补全（显示用法说明）
     * @param completion 补全上下文
     * @param cli 命令行接口定义
     */
    public static void completeUsage(Completion completion, CLI cli) {
        // 获取终端对象以确定宽度
        Tty tty = completion.session().get(Session.TTY);
        // 生成带样式的用法说明
        String usage = StyledUsageFormatter.styledUsage(cli, tty.width());
        // 补全用法说明
        completion.complete(Collections.singletonList(usage));
    }

    /**
     * 通用补全处理方法
     * @param completion 补全上下文
     * @param prefix 补全前缀
     * @param candidates 候选列表
     */
    private static void complete(Completion completion, String prefix, List<String> candidates) {
        if (candidates.size() == 1) {
            // 唯一候选，直接补全
            completion.complete(candidates.get(0).substring(prefix.length()), true);
        } else {
            // 多个候选，查找最长公共前缀
            String commonPrefix = CompletionUtils.findLongestCommonPrefix(candidates);
            if (commonPrefix.length() > 0) {
                if (commonPrefix.length() == prefix.length()) {
                    // 公共前缀等于原前缀，显示所有候选
                    completion.complete(candidates);
                } else {
                    // 公共前缀更长，补全公共前缀部分
                    completion.complete(commonPrefix.substring(prefix.length()), false);
                }
            } else {
                // 无公共前缀，显示所有候选
                completion.complete(candidates);
            }
        }
    }

    /**
     * 检查是否应该补全某个选项的参数
     * @param completion 补全上下文
     * @param option 选项名称
     * @return 是否应该补全
     */
    public static boolean shouldCompleteOption(Completion completion, String option) {
        List<CliToken> tokens = completion.lineTokens();
        // 情况1: 两个令牌，倒数第一个不是-开头，倒数第二个是选项
        if (tokens.size() >= 2) {
            CliToken cliToken_1 = tokens.get(tokens.size() - 1);
            CliToken cliToken_2 = tokens.get(tokens.size() - 2);
            String token_2 = cliToken_2.value();
            if (!cliToken_1.value().startsWith("-") && token_2.equals(option)) {
                return CompletionUtils.completeClassName(completion);
            }
        }
        // 情况2: 三个令牌，倒数第一个不是-开头，倒数第二个是空，倒数第三个是选项
        if (tokens.size() >= 3) {
            CliToken cliToken_1 = tokens.get(tokens.size() - 1);
            CliToken cliToken_2 = tokens.get(tokens.size() - 2);
            CliToken cliToken_3 = tokens.get(tokens.size() - 3);
            if (!cliToken_1.value().startsWith("-") && cliToken_2.isBlank()
                    && cliToken_3.value().equals(option)) {
                return CompletionUtils.completeClassName(completion);
            }
        }
        return false;
    }

    /**
     * 完成选项参数的自动补全
     * @param completion 补全上下文
     * @param handlers 选项补全处理器列表
     * @return 是否完成补全
     */
    public static boolean completeOptions(Completion completion, List<OptionCompleteHandler> handlers) {
        List<CliToken> tokens = completion.lineTokens();
        // 情况1: 三个或更多令牌，倒数第二个是空，倒数第三个是选项
        if (tokens.size() >= 3) {
            CliToken cliToken_2 = tokens.get(tokens.size() - 2);
            CliToken cliToken_3 = tokens.get(tokens.size() - 3);

            if (cliToken_2.isBlank()) {
                String token_3 = cliToken_3.value();
                // 查找匹配的选项补全处理器
                for (OptionCompleteHandler handler : handlers) {
                    if (handler.matchName(token_3)) {
                        return handler.complete(completion);
                    }
                }
            }
        }

        // 情况2: 两个或更多令牌，最后一个是空，前一个是选项
        if (tokens.size() >= 2) {
            CliToken cliToken_1 = tokens.get(tokens.size() - 1);
            CliToken cliToken_2 = tokens.get(tokens.size() - 2);
            if (cliToken_1.isBlank()) {
                String token_2 = cliToken_2.value();
                // 查找匹配的选项补全处理器
                for (OptionCompleteHandler handler : handlers) {
                    if (handler.matchName(token_2)) {
                        return handler.complete(completion);
                    }
                }
            }
        }

        return false;
    }
}