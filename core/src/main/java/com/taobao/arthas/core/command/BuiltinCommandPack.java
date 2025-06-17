package com.taobao.arthas.core.command;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.command.basic1000.*;
import com.taobao.arthas.core.command.hidden.JulyCommand;
import com.taobao.arthas.core.command.hidden.ThanksCommand;
import com.taobao.arthas.core.command.klass100.ClassLoaderCommand;
import com.taobao.arthas.core.command.klass100.DumpClassCommand;
import com.taobao.arthas.core.command.klass100.GetStaticCommand;
import com.taobao.arthas.core.command.klass100.JadCommand;
import com.taobao.arthas.core.command.klass100.MemoryCompilerCommand;
import com.taobao.arthas.core.command.klass100.OgnlCommand;
import com.taobao.arthas.core.command.klass100.RedefineCommand;
import com.taobao.arthas.core.command.klass100.RetransformCommand;
import com.taobao.arthas.core.command.klass100.SearchClassCommand;
import com.taobao.arthas.core.command.klass100.SearchMethodCommand;
import com.taobao.arthas.core.command.logger.LoggerCommand;
import com.taobao.arthas.core.command.monitor200.DashboardCommand;
import com.taobao.arthas.core.command.monitor200.HeapDumpCommand;
import com.taobao.arthas.core.command.monitor200.JvmCommand;
import com.taobao.arthas.core.command.monitor200.MBeanCommand;
import com.taobao.arthas.core.command.monitor200.MemoryCommand;
import com.taobao.arthas.core.command.monitor200.MonitorCommand;
import com.taobao.arthas.core.command.monitor200.PerfCounterCommand;
import com.taobao.arthas.core.command.monitor200.ProfilerCommand;
import com.taobao.arthas.core.command.monitor200.StackCommand;
import com.taobao.arthas.core.command.monitor200.ThreadCommand;
import com.taobao.arthas.core.command.monitor200.TimeTunnelCommand;
import com.taobao.arthas.core.command.monitor200.TraceCommand;
import com.taobao.arthas.core.command.monitor200.VmToolCommand;
import com.taobao.arthas.core.command.monitor200.WatchCommand;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.middleware.cli.annotations.Name;

/**
 * 内置命令包，注册Arthas所有内置命令
 * 目前通过手动方式注册命令类，未来计划实现自动发现机制
 *
 * @author beiwei30 on 17/11/2016.
 */
public class BuiltinCommandPack implements CommandResolver {
    private static final Logger logger = LoggerFactory.getLogger(BuiltinCommandPack.class);
    // 存储所有内置命令的列表
    private List<Command> commands = new ArrayList<Command>();

    /**
     * 构造函数，初始化命令列表
     *
     * @param disabledCommands 禁用的命令列表
     */
    public BuiltinCommandPack(List<String> disabledCommands) {
        initCommands(disabledCommands);
    }

    /**
     * 获取所有命令列表
     */
    @Override
    public List<Command> commands() {
        return commands;
    }

    /**
     * 初始化命令列表，支持禁用指定命令
     *
     * @param disabledCommands 禁用的命令列表
     */
    private void initCommands(List<String> disabledCommands) {
        // 定义命令类列表，按功能模块分组添加
        List<Class<? extends AnnotatedCommand>> commandClassList = new ArrayList<Class<? extends AnnotatedCommand>>(33);

        // 基础命令组（basic1000）
        commandClassList.add(HelpCommand.class);        // 帮助命令
        commandClassList.add(AuthCommand.class);       // 认证命令
        commandClassList.add(KeymapCommand.class);     // 快捷键映射命令
        commandClassList.add(OptionsCommand.class);     // 选项配置命令
        commandClassList.add(ClsCommand.class);        // 清屏命令
        commandClassList.add(ResetCommand.class);      // 重置命令
        commandClassList.add(VersionCommand.class);     // 版本命令
        commandClassList.add(SessionCommand.class);     // 会话命令
        commandClassList.add(SystemPropertyCommand.class); // 系统属性命令
        commandClassList.add(SystemEnvCommand.class);   // 系统环境命令
        commandClassList.add(VMOptionCommand.class);   // JVM选项命令
        commandClassList.add(HistoryCommand.class);     // 历史命令
        commandClassList.add(CatCommand.class);        // 查看文件内容命令
        commandClassList.add(Base64Command.class);     // Base64编码解码命令
        commandClassList.add(EchoCommand.class);       // 回显命令
        commandClassList.add(PwdCommand.class);        // 显示当前路径命令
        commandClassList.add(GrepCommand.class);       // 文本过滤命令
        commandClassList.add(TeeCommand.class);        // 重定向命令

        // 类操作命令组（klass100）
        commandClassList.add(SearchClassCommand.class); // 搜索类命令
        commandClassList.add(SearchMethodCommand.class);// 搜索方法命令
        commandClassList.add(ClassLoaderCommand.class); // 类加载器命令
        commandClassList.add(JadCommand.class);         // 反编译命令
        commandClassList.add(GetStaticCommand.class);   // 获取静态变量命令
        commandClassList.add(MemoryCompilerCommand.class);// 内存编译命令
        commandClassList.add(OgnlCommand.class);        // OGNL表达式命令
        commandClassList.add(RedefineCommand.class);    // 重定义类命令
        commandClassList.add(RetransformCommand.class);  // 重新转换类命令
        commandClassList.add(DumpClassCommand.class);   // 导出类命令

        // 监控命令组（monitor200）
        commandClassList.add(MonitorCommand.class);     // 方法监控命令
        commandClassList.add(StackCommand.class);       // 方法栈跟踪命令
        commandClassList.add(ThreadCommand.class);      // 线程查看命令
        commandClassList.add(TraceCommand.class);       // 方法调用跟踪命令
        commandClassList.add(WatchCommand.class);       // 方法观察命令
        commandClassList.add(TimeTunnelCommand.class);  // 时间隧道命令
        commandClassList.add(JvmCommand.class);         // JVM信息命令
        commandClassList.add(MemoryCommand.class);      // 内存信息命令
        commandClassList.add(PerfCounterCommand.class); // 性能计数器命令
        commandClassList.add(DashboardCommand.class);   // 仪表盘命令
        commandClassList.add(HeapDumpCommand.class);    // 堆转储命令
        commandClassList.add(MBeanCommand.class);       // MBean命令
        commandClassList.add(ProfilerCommand.class);    // 性能分析命令
        commandClassList.add(VmToolCommand.class);      // VM工具命令

        // 日志命令
        commandClassList.add(LoggerCommand.class);      // 日志查看与修改命令

        // 隐藏命令（内部使用）
        commandClassList.add(JulyCommand.class);        // 内部测试命令
        commandClassList.add(ThanksCommand.class);      // 感谢命令

        // JFR命令（仅JDK支持时添加）
        try {
            if (ClassLoader.getSystemClassLoader().getResource("jdk/jfr/Recording.class") != null) {
                commandClassList.add(JFRCommand.class); // JFR记录命令
            }
        } catch (Throwable e) {
            logger.error("This jdk version not support jfr command");
        }

        // 实例化并注册命令（跳过禁用的命令）
        for (Class<? extends AnnotatedCommand> clazz : commandClassList) {
            // 获取命令名称注解
            Name name = clazz.getAnnotation(Name.class);
            if (name != null && name.value() != null) {
                // 检查命令是否被禁用
                if (disabledCommands.contains(name.value())) {
                    continue;
                }
            }
            // 创建命令实例并添加到列表
            commands.add(Command.create(clazz));
        }
    }
}