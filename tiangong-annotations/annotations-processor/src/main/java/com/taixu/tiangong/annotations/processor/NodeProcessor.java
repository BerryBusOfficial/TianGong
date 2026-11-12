package com.taixu.tiangong.annotations.processor;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.util.List;

/**
 * 节点处理器，处理不同的节点
 */
public interface NodeProcessor {

    boolean support(NodeType type);

    List<Class<?>> supportReturnType();

    void process(Object instance, BeanDefinition definition);

}
