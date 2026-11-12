package org.wutong.tiangong.common.function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.serialize.arguments.ArgumentsDeserializer;
import org.wutong.tiangong.common.serialize.arguments.ArgumentsSerializer;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@RequiredArgsConstructor
@JsonSerialize(using = ArgumentsSerializer.class)
@JsonDeserialize(using = ArgumentsDeserializer.class)
public class Arguments {

    private final List<Parameter<?>> parameters;

    public record Parameter<T>(
            String paramName,
            T value
    ) {
    }

    /**
     * 把协议侧的 arguments 文本解析为 {@code Arguments}。<br>
     * <b>为什么需要它</b>：Ollama 在响应里直接给结构化对象，反序列化即可；但 OpenAI 协议
     * （以及 vLLM / LM Studio 等所有 OpenAI 兼容端点）<b>给的是 JSON 字符串</b>，
     * 而 {@code Arguments} 是按 Ollama 那种形状定义的。没有这个入口，每个用 OpenAI 协议
     * 的 provider 都要自己在应用层糊一遍转换。
     *
     * @param json arguments 文本；模型调无参工具时常常是空串，此时返回空 Arguments
     */
    public static Arguments fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new Arguments(List.of());
        }
        try {
            return JsonUtil.fromJson(json, Arguments.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法解析工具参数: %s".formatted(json), e);
        }
    }

    /**
     * 摊平成「参数名 → 值」的 Map。<br>
     * <b>为什么必须有它</b>：{@code Arguments} 装的是 {@code List<Parameter<?>>}，<b>不是 Map</b>。
     * 直接 {@code JsonUtil.convertToMap(arguments)} 会得到 {@code {"parameters":[{...}]}}，
     * 于是每个参数取出来都是 {@code null} —— 工具照常被调用、照常返回，只是拿到一堆空值，
     * 没有任何异常。工具实现要读参数，一律走这里。
     *
     * @return 参数名到值的映射；同名参数后者覆盖前者，顺序与协议侧一致
     */
    public Map<String, Object> asMap() {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Parameter<?> parameter : parameters) {
            if (parameter == null || parameter.paramName() == null) {
                continue;
            }
            map.put(parameter.paramName(), parameter.value());
        }
        return map;
    }

    /**
     * 取单个参数值；不存在时返回 {@code null}。类型由调用方确认 ——
     * 框架的工具调用<b>不做实参类型转换</b>（见 {@code MethodInvoker}），
     * 协议里 JSON 数字可能是 Integer / Long / Double，别想当然。
     */
    public Object get(String paramName) {
        return asMap().get(paramName);
    }

}
