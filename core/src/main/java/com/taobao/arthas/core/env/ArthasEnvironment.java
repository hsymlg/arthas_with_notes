package com.taobao.arthas.core.env;

import java.security.AccessControlException;
import java.util.Map;

/**
 * Arthas环境实现类，实现Environment接口
 *
 * 管理属性源(PropertySource)，提供系统属性和环境变量的访问
 * 实现了属性解析的具体逻辑
 *
 * @author hengyunabc 2019-12-27
 */
public class ArthasEnvironment implements Environment {
    /** 系统环境属性源名称 */
    public static final String SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME = "systemEnvironment";

    /** JVM系统属性属性源名称 */
    public static final String SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME = "systemProperties";

    // 可变属性源集合，用于管理多个属性源
    private final MutablePropertySources propertySources = new MutablePropertySources();

    // 属性解析器，基于属性源集合实现属性解析
    private final ConfigurablePropertyResolver propertyResolver = new PropertySourcesPropertyResolver(
            this.propertySources);

    /**
     * 构造函数，初始化Arthas环境
     * 添加系统环境和系统属性作为默认属性源
     */
    public ArthasEnvironment() {
        // 添加系统环境属性源（优先级较低，最后添加）
        propertySources.addLast(
                new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, getSystemEnvironment()));
        // 添加系统属性属性源（优先级较低，最后添加）
        propertySources
                .addLast(new PropertiesPropertySource(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, getSystemProperties()));
    }

    /**
     * 添加属性源到最前面（最高优先级）
     *
     * @param propertySource 要添加的属性源
     */
    public void addFirst(PropertySource<?> propertySource) {
        this.propertySources.addFirst(propertySource);
    }

    /**
     * 添加属性源到最后面（最低优先级）
     *
     * @param propertySource 要添加的属性源
     */
    public void addLast(PropertySource<?> propertySource) {
        this.propertySources.addLast(propertySource);
    }

    /**
     * 获取系统属性（java系统属性，如java.version）
     * 处理访问控制异常，提供只读访问
     *
     * @return 系统属性的Map表示
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map<String, Object> getSystemProperties() {
        try {
            // 直接获取系统属性
            return (Map) System.getProperties();
        } catch (AccessControlException ex) {
            // 访问被拒绝时，返回只读的系统属性映射
            return (Map) new ReadOnlySystemAttributesMap() {
                @Override
                protected String getSystemAttribute(String attributeName) {
                    try {
                        // 尝试获取指定系统属性
                        return System.getProperty(attributeName);
                    } catch (AccessControlException ex) {
                        // 访问失败时返回null
                        return null;
                    }
                }
            };
        }
    }

    /**
     * 获取系统环境变量（如PATH、USER_HOME等）
     * 处理访问控制异常，提供只读访问
     *
     * @return 系统环境变量的Map表示
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Map<String, Object> getSystemEnvironment() {
        try {
            // 直接获取系统环境变量
            return (Map) System.getenv();
        } catch (AccessControlException ex) {
            // 访问被拒绝时，返回只读的系统环境映射
            return (Map) new ReadOnlySystemAttributesMap() {
                @Override
                protected String getSystemAttribute(String attributeName) {
                    try {
                        // 尝试获取指定系统环境变量
                        return System.getenv(attributeName);
                    } catch (AccessControlException ex) {
                        // 访问失败时返回null
                        return null;
                    }
                }
            };
        }
    }

    // ---------------------------------------------------------------------
    // PropertyResolver接口实现
    // ---------------------------------------------------------------------

    @Override
    public boolean containsProperty(String key) {
        // 委托给属性解析器实现
        return this.propertyResolver.containsProperty(key);
    }

    @Override
    public String getProperty(String key) {
        // 委托给属性解析器实现
        return this.propertyResolver.getProperty(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        // 委托给属性解析器实现
        return this.propertyResolver.getProperty(key, defaultValue);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType) {
        // 委托给属性解析器实现
        return this.propertyResolver.getProperty(key, targetType);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        // 委托给属性解析器实现
        return this.propertyResolver.getProperty(key, targetType, defaultValue);
    }

    @Override
    public String getRequiredProperty(String key) throws IllegalStateException {
        // 委托给属性解析器实现
        return this.propertyResolver.getRequiredProperty(key);
    }

    @Override
    public <T> T getRequiredProperty(String key, Class<T> targetType) throws IllegalStateException {
        // 委托给属性解析器实现
        return this.propertyResolver.getRequiredProperty(key, targetType);
    }

    @Override
    public String resolvePlaceholders(String text) {
        // 委托给属性解析器实现
        return this.propertyResolver.resolvePlaceholders(text);
    }

    @Override
    public String resolveRequiredPlaceholders(String text) throws IllegalArgumentException {
        // 委托给属性解析器实现
        return this.propertyResolver.resolveRequiredPlaceholders(text);
    }
}