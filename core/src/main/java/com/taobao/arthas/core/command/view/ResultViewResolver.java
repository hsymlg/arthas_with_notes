package com.taobao.arthas.core.command.view;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.command.model.ResultModel;
import com.taobao.arthas.core.shell.command.CommandProcess;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端结果视图解析器
 * 负责将不同类型的ResultModel映射到对应的ResultView
 * 以便将命令执行结果以合适的格式展示给用户
 *
 * @author gongdewei 2020/3/27
 */
public class ResultViewResolver {
    private static final Logger logger = LoggerFactory.getLogger(ResultViewResolver.class);

    // 存储ResultModel类与对应ResultView的映射关系
    // 键为ResultModel的Class对象，值为对应的ResultView实例
    private Map<Class, ResultView> resultViewMap = new ConcurrentHashMap<Class, ResultView>();

    /**
     * 构造函数，初始化注册所有内置的ResultView
     */
    public ResultViewResolver() {
        initResultViews();
    }

    /**
     * 初始化并注册所有内置的ResultView
     * 按功能模块分组注册不同类型命令的视图处理器
     */
    private void initResultViews() {
        try {
            // 注册基础命令视图 - 影响行数统计视图
            registerView(RowAffectView.class);

            // 基础命令组(1000系列) - 状态、版本、帮助等基础功能
            registerView(StatusView.class);
            registerView(VersionView.class);
            registerView(MessageView.class);
            registerView(HelpView.class);
            //registerView(HistoryView.class);
            registerView(EchoView.class);
            registerView(CatView.class);
            registerView(Base64View.class);
            registerView(OptionsView.class);
            registerView(SystemPropertyView.class);
            registerView(SystemEnvView.class);
            registerView(PwdView.class);
            registerView(VMOptionView.class);
            registerView(SessionView.class);
            registerView(ResetView.class);
            registerView(ShutdownView.class);

            // 类相关命令组(100系列) - 类加载、反编译、重定义等功能
            registerView(ClassLoaderView.class);
            registerView(DumpClassView.class);
            registerView(GetStaticView.class);
            registerView(JadView.class);
            registerView(MemoryCompilerView.class);
            registerView(OgnlView.class);
            registerView(RedefineView.class);
            registerView(RetransformView.class);
            registerView(SearchClassView.class);
            registerView(SearchMethodView.class);

            // 日志相关命令组
            registerView(LoggerView.class);

            // 监控命令组(2000系列) - 各种监控和诊断功能
            registerView(DashboardView.class);
            registerView(JvmView.class);
            registerView(MemoryView.class);
            registerView(MBeanView.class);
            registerView(PerfCounterView.class);
            registerView(ThreadView.class);
            registerView(ProfilerView.class);
            registerView(EnhancerView.class);
            registerView(MonitorView.class);
            registerView(StackView.class);
            registerView(TimeTunnelView.class);
            registerView(TraceView.class);
            registerView(WatchView.class);
            registerView(VmToolView.class);
            registerView(JFRView.class);

        } catch (Throwable e) {
            // 记录视图注册过程中的异常，确保一个视图注册失败不会影响其他视图
            logger.error("register result view failed", e);
        }
    }

    /**
     * 根据ResultModel获取对应的ResultView
     * @param model 命令执行结果模型
     * @return 对应的视图处理器，如果没有找到则返回null
     */
    public ResultView getResultView(ResultModel model) {
        return resultViewMap.get(model.getClass());
    }

    /**
     * 注册ResultModel与ResultView的映射关系
     * @param modelClass ResultModel的Class对象
     * @param view 对应的ResultView实例
     * @return 当前ResultViewResolver实例，支持链式调用
     */
    public ResultViewResolver registerView(Class modelClass, ResultView view) {
        // TODO 检查model的type是否重复，避免复制代码带来的bug
        this.resultViewMap.put(modelClass, view);
        return this;
    }

    /**
     * 注册ResultView，自动检测关联的ResultModel类型
     * @param view 要注册的ResultView实例
     * @return 当前ResultViewResolver实例，支持链式调用
     */
    public ResultViewResolver registerView(ResultView view) {
        // 通过反射获取视图对应的ResultModel类型
        Class modelClass = getModelClass(view);
        if (modelClass == null) {
            throw new NullPointerException("model class is null");
        }
        return this.registerView(modelClass, view);
    }

    /**
     * 通过视图类注册ResultView，自动创建实例并关联
     * @param viewClass 视图类的Class对象
     */
    public void registerView(Class<? extends ResultView> viewClass) {
        ResultView view = null;
        try {
            // 使用反射创建视图实例
            view = viewClass.newInstance();
        } catch (Throwable e) {
            throw new RuntimeException("create view instance failure, viewClass:" + viewClass, e);
        }
        // 注册视图实例
        this.registerView(view);
    }

    /**
     * 通过反射获取ResultView关联的ResultModel类型
     * 查找ResultView实现的draw方法，获取其第二个参数的类型
     * 该参数类型必须是ResultModel的子类，但不能是ResultModel本身
     *
     * @param view ResultView实例
     * @return 关联的ResultModel类型，如果未找到则返回null
     */
    public static <V extends ResultView> Class getModelClass(V view) {
        // 获取视图实例的运行时类
        Class<? extends ResultView> viewClass = view.getClass();

        // 获取该类声明的所有方法（包括私有、受保护和公共方法，但不包括继承的方法）
        Method[] declaredMethods = viewClass.getDeclaredMethods();

        // 遍历所有声明的方法
        for (int i = 0; i < declaredMethods.length; i++) {
            // 获取当前方法
            Method method = declaredMethods[i];

            // 检查方法名称是否为"draw"
            if (method.getName().equals("draw")) {
                // 获取方法的参数类型数组
                Class<?>[] parameterTypes = method.getParameterTypes();

                // 检查方法是否满足以下条件：
                // 1. 恰好有两个参数
                // 2. 第一个参数类型是CommandProcess
                // 3. 第二个参数类型不是ResultModel本身（必须是子类）
                // 4. 第二个参数类型是ResultModel的子类或实现类
                if (parameterTypes.length == 2
                        && parameterTypes[0] == CommandProcess.class
                        && parameterTypes[1] != ResultModel.class
                        && ResultModel.class.isAssignableFrom(parameterTypes[1])) {

                    // 满足所有条件时，返回第二个参数的类型（即关联的ResultModel类型）
                    return parameterTypes[1];
                }
            }
        }

        // 如果未找到符合条件的方法，返回null
        return null;
    }
}