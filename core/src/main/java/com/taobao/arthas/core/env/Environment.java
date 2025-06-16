package com.taobao.arthas.core.env;

/**
 * 环境接口，继承自PropertyResolver，符合 "环境即属性集合" 的抽象
 *
 * 表示运行环境，提供环境相关的属性解析功能
 * 通常用于获取系统环境、配置等属性
 *
 * 采用 "接口继承" 模式扩展功能
 */
public interface Environment extends PropertyResolver {
    // 接口继承自PropertyResolver，无需额外方法
}