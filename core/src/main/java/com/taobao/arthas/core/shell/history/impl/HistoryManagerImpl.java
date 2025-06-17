package com.taobao.arthas.core.shell.history.impl;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.shell.history.HistoryManager;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令历史记录管理器实现类
 * 负责管理Arthas命令行的历史记录，包括加载、保存、清除和添加历史命令
 *
 * @see io.termd.core.readline.Readline#history 参考终端读行组件的历史记录功能
 * @author gongdewei 2020/4/8
 */
public class HistoryManagerImpl implements HistoryManager {
    /**
     * 内存中保存的最大历史记录数量
     * 当历史记录超过此数量时，会移除最早的记录
     */
    private static final int MAX_HISTORY_SIZE = 500;

    private static final Logger logger = LoggerFactory.getLogger(HistoryManagerImpl.class);

    // 存储命令历史记录的列表，使用ArrayList实现
    private List<String> history = new ArrayList<String>();

    /**
     * 构造函数，初始化历史记录管理器
     */
    public HistoryManagerImpl() {
    }

    @Override
    public synchronized void saveHistory() {
        try {
            // 调用工具类将历史记录保存到文件
            FileUtils.saveCommandHistoryString(history, new File(Constants.CMD_HISTORY_FILE));
        } catch (Throwable e) {
            // 记录保存历史记录时的异常
            logger.error("save command history failed", e);
        }
    }

    @Override
    public synchronized void loadHistory() {
        try {
            // 调用工具类从文件加载历史记录
            history = FileUtils.loadCommandHistoryString(new File(Constants.CMD_HISTORY_FILE));
        } catch (Throwable e) {
            // 记录加载历史记录时的异常
            logger.error("load command history failed", e);
        }
    }

    @Override
    public synchronized void clearHistory() {
        // 清空历史记录列表
        this.history.clear();
    }

    @Override
    public synchronized void addHistory(String commandLine) {
        // 当历史记录数量达到最大值时，移除最早的记录
        while (history.size() >= MAX_HISTORY_SIZE) {
            history.remove(0);
        }
        // 添加新的命令到历史记录列表
        history.add(commandLine);
    }

    @Override
    public synchronized List<String> getHistory() {
        // 返回历史记录的副本，避免外部直接修改原始列表
        return new ArrayList<String>(history);
    }

    @Override
    public synchronized void setHistory(List<String> history) {
        // 设置历史记录列表
        this.history = history;
    }
}