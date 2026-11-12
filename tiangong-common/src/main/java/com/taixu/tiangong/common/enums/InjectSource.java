package com.taixu.tiangong.common.enums;

/**
 * 注入来源类型（@Inject 的 source 分派）。<br>
 * BEAN：容器 Bean 注入；LLM：LlmRegistry 客户端；REQUEST：请求构建器；RESOURCE：classpath 资源。
 */
public enum InjectSource {
    BEAN,
    LLM,
    REQUEST,
    RESOURCE,
}
