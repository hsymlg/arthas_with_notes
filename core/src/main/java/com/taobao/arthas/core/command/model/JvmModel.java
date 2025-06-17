package com.taobao.arthas.core.command.model;

import java.util.*;

/**
 * Model of 'jvm' command
 * 该类用于表示 'jvm' 命令的模型，存储与JVM相关的信息
 *
 * @author gongdewei 2020/4/24
 */
public class JvmModel extends ResultModel {

    // 用于存储JVM信息的映射，键为组名，值为该组下的JvmItemVO列表
    private Map<String, List<JvmItemVO>> jvmInfo;

    // 构造函数，初始化 jvmInfo 为一个同步的 LinkedHashMap，确保线程安全并保持插入顺序
    public JvmModel() {
        // 使用 Collections.synchronizedMap 方法创建一个线程安全的 LinkedHashMap
        jvmInfo = Collections.synchronizedMap(new LinkedHashMap<String, List<JvmItemVO>>());
    }

    /**
     * 重写父类方法，返回模型的类型
     * @return 模型类型为 "jvm"
     */
    @Override
    public String getType() {
        return "jvm";
    }

    /**
     * 向指定组添加一个JVM信息项，描述信息默认为 null
     * @param group 信息项所属的组名
     * @param name  信息项的名称
     * @param value 信息项的值
     * @return 当前 JvmModel 对象，以便链式调用
     */
    public JvmModel addItem(String group, String name, Object value) {
        // 调用另一个 addItem 方法，描述信息设为 null
        this.addItem(group, name, value, null);
        return this;
    }

    /**
     * 向指定组添加一个带有描述信息的JVM信息项
     * @param group 信息项所属的组名
     * @param name  信息项的名称
     * @param value 信息项的值
     * @param desc  信息项的描述信息
     * @return 当前 JvmModel 对象，以便链式调用
     */
    public JvmModel  addItem(String group, String name, Object value, String desc) {
        // 调用 group 方法获取指定组的列表，并添加一个新的 JvmItemVO 对象
        this.group(group).add(new JvmItemVO(name, value, desc));
        return this;
    }

    /**
     * 获取指定组的 JvmItemVO 列表，如果该组不存在，则创建一个新的列表并添加到 jvmInfo 中
     * @param group 组名
     * @return 指定组的 JvmItemVO 列表
     */
    public List<JvmItemVO> group(String group) {
        // 使用同步块确保线程安全
        synchronized (this) {
            // 从 jvmInfo 中获取指定组的列表
            List<JvmItemVO> list = jvmInfo.get(group);
            // 如果该组的列表不存在
            if (list == null) {
                // 创建一个新的 ArrayList
                list = new ArrayList<JvmItemVO>();
                // 将该组及其对应的列表添加到 jvmInfo 中
                jvmInfo.put(group, list);
            }
            return list;
        }
    }

    /**
     * 获取存储JVM信息的映射
     * @return 存储JVM信息的映射
     */
    public Map<String, List<JvmItemVO>> getJvmInfo() {
        return jvmInfo;
    }

}