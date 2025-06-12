package com.taobao.arthas.common;
// 包声明，指定该类所属的包结构

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
// 导入该类所需的所有外部类，涵盖IO操作、异常处理和集合等功能模块

/**
 * 执行命令行命令并返回执行结果的工具类
 *
 * 提供了在本地命令行执行命令并获取结果的功能，支持按行读取命令输出
 *
 * @author alessandro[at]perucchi[dot]org
 */
public class ExecutingCommand {
    // 类定义，ExecutingCommand 作为命令行执行的工具类

    // 私有构造函数，防止实例化
    private ExecutingCommand() {
        // 空构造函数，防止外部实例化该工具类
    }

    /**
     * 在本地命令行执行单个字符串命令并返回结果
     *
     * 将命令字符串按空格分割后执行，适用于简单命令
     *
     * @param cmdToRun 要执行的命令字符串（如 "ls -la"）
     * @return 命令执行结果的行列表，命令失败时返回空列表
     */
    public static List<String> runNative(String cmdToRun) {
        // 将命令字符串按空格分割为命令和参数数组
        String[] cmd = cmdToRun.split(" ");
        // 调用数组参数版本的runNative方法
        return runNative(cmd);
    }

    /**
     * 在本地命令行执行命令并按行返回结果
     *
     * 直接使用命令和参数数组执行，适用于包含空格的参数
     *
     * @param cmdToRunWithArgs 命令和参数数组（如 ["ls", "-la"]）
     * @return 命令执行结果的行列表，命令失败时返回空列表
     */
    public static List<String> runNative(String[] cmdToRunWithArgs) {
        Process p = null;
        try {
            // 使用Runtime.exec执行本地命令
            p = Runtime.getRuntime().exec(cmdToRunWithArgs);
        } catch (SecurityException e) {
            // 安全管理器禁止执行命令时的异常处理
            AnsiLog.trace("无法运行命令 {}:", Arrays.toString(cmdToRunWithArgs));
            AnsiLog.trace(e);
            return new ArrayList<String>(0); // 返回空列表
        } catch (IOException e) {
            // IO异常处理（如命令不存在）
            AnsiLog.trace("无法运行命令 {}:", Arrays.toString(cmdToRunWithArgs));
            AnsiLog.trace(e);
            return new ArrayList<String>(0); // 返回空列表
        }

        // 创建存储命令输出的列表
        ArrayList<String> sa = new ArrayList<String>();
        // 创建输入流读取器，用于读取命令的标准输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            // 逐行读取命令输出并添加到列表
            while ((line = reader.readLine()) != null) {
                sa.add(line);
            }
            // 等待命令执行完成
            p.waitFor();
        } catch (IOException e) {
            // 读取输出流异常处理
            AnsiLog.trace("读取命令 {} 的输出时出错:", Arrays.toString(cmdToRunWithArgs));
            AnsiLog.trace(e);
            return new ArrayList<String>(0); // 返回空列表
        } catch (InterruptedException ie) {
            // 线程中断异常处理
            AnsiLog.trace("读取命令 {} 的输出时出错:", Arrays.toString(cmdToRunWithArgs));
            AnsiLog.trace(ie);
            Thread.currentThread().interrupt(); // 恢复中断状态
        } finally {
            // 确保输入流读取器关闭
            IOUtils.close(reader);
        }
        return sa; // 返回命令输出的行列表
    }

    /**
     * 获取命令执行结果的第一行
     *
     * 适用于只需要命令输出第一行的场景
     *
     * @param cmd2launch 要执行的命令字符串
     * @return 命令输出的第一行，命令失败或无输出时返回空字符串
     */
    public static String getFirstAnswer(String cmd2launch) {
        // 调用getAnswerAt方法获取第0行（第一行）
        return getAnswerAt(cmd2launch, 0);
    }

    /**
     * 获取命令执行结果指定行的内容
     *
     * 根据索引获取命令输出中的特定行
     *
     * @param cmd2launch 要执行的命令字符串
     * @param answerIdx 要获取的行索引（从0开始）
     * @return 指定索引的行内容，索引无效或命令失败时返回空字符串
     */
    public static String getAnswerAt(String cmd2launch, int answerIdx) {
        // 执行命令并获取所有输出行
        List<String> sa = ExecutingCommand.runNative(cmd2launch);

        // 检查索引是否有效
        if (answerIdx >= 0 && answerIdx < sa.size()) {
            return sa.get(answerIdx); // 返回指定索引的行
        }
        return ""; // 索引无效时返回空字符串
    }
}