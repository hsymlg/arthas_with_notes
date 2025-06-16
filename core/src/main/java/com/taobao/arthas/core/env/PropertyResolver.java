/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.taobao.arthas.core.env;

/**
 * 属性解析器接口，定义了从底层源解析属性的方法
 *
 * 该接口提供了一系列方法用于获取、解析属性值，支持属性占位符解析
 *
 * 功能边界：仅负责属性的查询、类型转换、占位符解析
 * 避免功能膨胀：不涉及属性源的管理（如添加、删除属性源），该职责由实现类承担
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see Environment 环境接口，继承自PropertyResolver
 * @see PropertySourcesPropertyResolver 属性源解析器实现
 */
public interface PropertyResolver {

    /**
     * 判断给定的属性键是否存在（即属性值不为null）
     *
     * @param key 属性键
     * @return 属性是否存在
     */
    boolean containsProperty(String key);

    /**
     * 获取给定属性键对应的属性值，若属性不存在则返回null
     *
     * @param key 属性键
     * @return 属性值，不存在时返回null
     * @see #getProperty(String, String) 带默认值的属性获取
     * @see #getProperty(String, Class) 带类型转换的属性获取
     * @see #getRequiredProperty(String) 获取必须存在的属性
     */
    String getProperty(String key);

    /**
     * 获取给定属性键对应的属性值，若属性不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 属性不存在时的默认值
     * @return 属性值或默认值
     * @see #getRequiredProperty(String) 获取必须存在的属性
     * @see #getProperty(String, Class) 带类型转换的属性获取
     */
    String getProperty(String key, String defaultValue);

    /**
     * 获取给定属性键对应的属性值，并转换为指定类型，若属性不存在则返回null
     *
     * @param key        属性键
     * @param targetType 目标类型
     * @return 转换后的属性值，不存在时返回null
     * @see #getRequiredProperty(String, Class) 获取必须存在的属性并转换类型
     */
    <T> T getProperty(String key, Class<T> targetType);

    /**
     * 获取给定属性键对应的属性值，转换为指定类型，若属性不存在则返回默认值
     *
     * @param key          属性键
     * @param targetType   目标类型
     * @param defaultValue 属性不存在时的默认值
     * @return 转换后的属性值或默认值
     * @see #getRequiredProperty(String, Class) 获取必须存在的属性并转换类型
     */
    <T> T getProperty(String key, Class<T> targetType, T defaultValue);

    /**
     * 获取必须存在的属性值（属性不存在时抛出异常）
     *
     * @param key 属性键
     * @return 属性值
     * @throws IllegalStateException 属性不存在时抛出异常
     * @see #getRequiredProperty(String, Class) 获取必须存在的属性并转换类型
     */
    String getRequiredProperty(String key) throws IllegalStateException;

    /**
     * 获取必须存在的属性值并转换为指定类型（属性不存在或类型转换失败时抛出异常）
     *
     * @param key        属性键
     * @param targetType 目标类型
     * @return 转换后的属性值
     * @throws IllegalStateException 属性不存在或类型转换失败时抛出异常
     */
    <T> T getRequiredProperty(String key, Class<T> targetType) throws IllegalStateException;

    /**
     * 解析字符串中的${...}占位符，替换为对应的属性值
     * 未解析的占位符（无默认值）将保留不变
     *
     * @param text 包含占位符的字符串
     * @return 解析后的字符串（不会为null）
     * @throws IllegalArgumentException 输入text为null时抛出异常
     * @see #resolveRequiredPlaceholders 解析必须成功的占位符
     * @see org.springframework.util.SystemPropertyUtils#resolvePlaceholders(String) Spring系统属性解析工具
     */
    String resolvePlaceholders(String text);

    /**
     * 解析字符串中的${...}占位符，替换为对应的属性值
     * 未解析的占位符（无默认值）将抛出异常
     *
     * @param text 包含占位符的字符串
     * @return 解析后的字符串（不会为null）
     * @throws IllegalArgumentException 输入text为null或有未解析的占位符时抛出异常
     * @see org.springframework.util.SystemPropertyUtils#resolvePlaceholders(String, boolean) Spring系统属性解析工具
     */
    String resolveRequiredPlaceholders(String text) throws IllegalArgumentException;
}