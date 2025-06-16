package java.arthas;

/**
 * SpyAPI 是 Arthas 实现字节码增强的核心接口，负责在目标方法中插入监控逻辑。
 * 该类被加载到 Bootstrap ClassLoader 中，确保所有类加载器都能访问它。
 * 通过静态方法提供切面通知点，允许在方法执行的不同阶段插入自定义逻辑。
 *
 * SpyAPI (核心接口)
 * ├── 静态变量
 * │   ├── NOPSPY (空实现单例)
 * │   ├── spyInstance (实际使用的 Spy 实例)
 * │   └── INITED (初始化标志)
 * │
 * ├── 生命周期管理
 * │   ├── init() (初始化)
 * │   ├── destroy() (销毁)
 * │   ├── isInited() (检查初始化状态)
 * │   └── setNopSpy() (设置为空实现)
 * │
 * ├── 代理方法
 * │   ├── getSpy() (获取当前 Spy 实例)
 * │   └── setSpy() (设置 Spy 实例)
 * │
 * ├── 切面通知点
 * │   ├── atEnter() (方法进入时)
 * │   ├── atExit() (方法正常退出时)
 * │   ├── atExceptionExit() (方法异常退出时)
 * │   ├── atBeforeInvoke() (调用其他方法前)
 * │   ├── atAfterInvoke() (调用其他方法后)
 * │   └── atInvokeException() (调用其他方法异常时)
 * │
 * ├── 抽象接口
 * │   └── AbstractSpy (定义所有通知点的抽象方法)
 * │
 * └── 默认实现
 *     └── NopSpy (空实现，所有方法为空)
 *
 *  静态代理是一种设计模式，核心思想是通过一个代理对象控制对真实对象的访问。代理对象和真实对象实现相同的接口，客户端通过代理对象间接调用真实对象的方法，代理对象可以在调用前后添加额外逻辑（如日志、权限控制等）。
 *  静态代理模式对原有代码无任何依赖，只需在字节码层面插入调用，应用代码无需知道 Spy 的存在，完全透明
 *  客户端直接调用 SpyAPI.atEnter()，而不是通过实例，整个应用共享同一个 SpyAPI 代理入口，通过 spyInstance 指向不同的真实实现
 *
 */
public class SpyAPI {
    // 默认实现为空操作，避免空指针异常，用于未启用监控或监控已关闭的场景
    public static final AbstractSpy NOPSPY = new NopSpy();
    // 实际使用的 Spy 实例，使用 volatile 确保多线程可见性
    private static volatile AbstractSpy spyInstance = NOPSPY;

    // 初始化标志，用于判断 SpyAPI 是否已正确初始化
    public static volatile boolean INITED;

    /**
     * 获取当前使用的 Spy 实例
     */
    public static AbstractSpy getSpy() {
        return spyInstance;
    }

    /**
     * 设置实际的 Spy 实现，通常在 Arthas 初始化时调用
     */
    public static void setSpy(AbstractSpy spy) {
        spyInstance = spy;
    }

    /**
     * 将 Spy 重置为默认空实现，释放资源
     */
    public static void setNopSpy() {
        setSpy(NOPSPY);
    }

    /**
     * 判断当前是否使用空实现（未初始化或已销毁）
     */
    public static boolean isNopSpy() {
        return NOPSPY == spyInstance;
    }

    /**
     * 标记 SpyAPI 已初始化
     */
    public static void init() {
        INITED = true;
    }

    /**
     * 检查 SpyAPI 是否已初始化
     */
    public static boolean isInited() {
        return INITED;
    }

    /**
     * 销毁 SpyAPI，重置状态并释放资源
     */
    public static void destroy() {
        setNopSpy();
        INITED = false;
    }

    /**
     * 在目标方法进入时调用（方法执行前）
     * @param clazz 目标类
     * @param methodInfo 方法信息（如方法签名）
     * @param target 目标对象实例（静态方法为 null）
     * @param args 方法参数
     */
    public static void atEnter(Class<?> clazz, String methodInfo, Object target, Object[] args) {
        spyInstance.atEnter(clazz, methodInfo, target, args);
    }

    /**
     * 在目标方法正常退出时调用（方法返回后）
     * @param clazz 目标类
     * @param methodInfo 方法信息
     * @param target 目标对象实例
     * @param args 方法参数
     * @param returnObject 方法返回值
     */
    public static void atExit(Class<?> clazz, String methodInfo, Object target, Object[] args,
                              Object returnObject) {
        spyInstance.atExit(clazz, methodInfo, target, args, returnObject);
    }

    /**
     * 在目标方法抛出异常退出时调用
     * @param clazz 目标类
     * @param methodInfo 方法信息
     * @param target 目标对象实例
     * @param args 方法参数
     * @param throwable 抛出的异常
     */
    public static void atExceptionExit(Class<?> clazz, String methodInfo, Object target,
                                       Object[] args, Throwable throwable) {
        spyInstance.atExceptionExit(clazz, methodInfo, target, args, throwable);
    }

    /**
     * 在目标方法调用其他方法前调用（方法内部调用点）
     * @param clazz 目标类
     * @param invokeInfo 被调用方法信息
     * @param target 目标对象实例
     */
    public static void atBeforeInvoke(Class<?> clazz, String invokeInfo, Object target) {
        spyInstance.atBeforeInvoke(clazz, invokeInfo, target);
    }

    /**
     * 在目标方法调用其他方法后调用（方法内部调用点）
     * @param clazz 目标类
     * @param invokeInfo 被调用方法信息
     * @param target 目标对象实例
     */
    public static void atAfterInvoke(Class<?> clazz, String invokeInfo, Object target) {
        spyInstance.atAfterInvoke(clazz, invokeInfo, target);
    }

    /**
     * 在目标方法调用其他方法抛出异常时调用
     * @param clazz 目标类
     * @param invokeInfo 被调用方法信息
     * @param target 目标对象实例
     * @param throwable 抛出的异常
     */
    public static void atInvokeException(Class<?> clazz, String invokeInfo, Object target, Throwable throwable) {
        spyInstance.atInvokeException(clazz, invokeInfo, target, throwable);
    }

    /**
     * Spy 接口的抽象实现，定义所有通知点的抽象方法
     */
    public static abstract class AbstractSpy { // 修正：添加 static 修饰符
        public abstract void atEnter(Class<?> clazz, String methodInfo, Object target, Object[] args);

        public abstract void atExit(Class<?> clazz, String methodInfo, Object target, Object[] args, Object returnObject);

        public abstract void atExceptionExit(Class<?> clazz, String methodInfo, Object target, Object[] args, Throwable throwable);

        public abstract void atBeforeInvoke(Class<?> clazz, String invokeInfo, Object target);

        public abstract void atAfterInvoke(Class<?> clazz, String invokeInfo, Object target);

        public abstract void atInvokeException(Class<?> clazz, String invokeInfo, Object target, Throwable throwable);
    }

    /**
     * 空实现的 Spy，所有方法不执行任何操作
     * 用于未启用监控或监控已关闭的场景
     */
    static class NopSpy extends AbstractSpy { // 修正：确保继承关系正确

        @Override
        public void atEnter(Class<?> clazz, String methodInfo, Object target, Object[] args) {
            // 空实现
        }

        @Override
        public void atExit(Class<?> clazz, String methodInfo, Object target, Object[] args, Object returnObject) {
            // 空实现
        }

        @Override
        public void atExceptionExit(Class<?> clazz, String methodInfo, Object target, Object[] args, Throwable throwable) {
            // 空实现
        }

        @Override
        public void atBeforeInvoke(Class<?> clazz, String invokeInfo, Object target) {
            // 空实现
        }

        @Override
        public void atAfterInvoke(Class<?> clazz, String invokeInfo, Object target) {
            // 空实现
        }

        @Override
        public void atInvokeException(Class<?> clazz, String invokeInfo, Object target, Throwable throwable) {
            // 空实现
        }
    }
}