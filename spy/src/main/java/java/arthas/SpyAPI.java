package java.arthas;

/**
 * SpyAPI 是 Arthas 实现字节码增强的核心接口，负责在目标方法中插入监控逻辑。
 * 该类被加载到 Bootstrap ClassLoader 中，确保所有类加载器都能访问它。
 * 通过静态方法提供切面通知点，允许在方法执行的不同阶段插入自定义逻辑。
 */
public class SpyAPI {
    // 默认实现为空操作，避免空指针异常
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