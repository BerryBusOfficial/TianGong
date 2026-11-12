package org.wutong.tiangong.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.wutong.tiangong.common.util.JsonUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * MCP JsonMapper 实现：基于项目已有 Jackson 2 的 {@link JsonUtil}（避免引入 Jackson 3 依赖）。
 */
public class TianGongMcpJsonMapper implements McpJsonMapper {

    @Override
    public <T> T readValue(String json, Class<T> clazz) throws IOException {
        return JsonUtil.fromJson(json, clazz);
    }

    @Override
    public <T> T readValue(byte[] bytes, Class<T> clazz) throws IOException {
        return JsonUtil.fromJson(new String(bytes, StandardCharsets.UTF_8), clazz);
    }

    @Override
    public <T> T readValue(String json, TypeRef<T> typeRef) throws IOException {
        return JsonUtil.fromJson(json, toTypeReference(typeRef));
    }

    @Override
    public <T> T readValue(byte[] bytes, TypeRef<T> typeRef) throws IOException {
        return JsonUtil.fromJson(new String(bytes, StandardCharsets.UTF_8), toTypeReference(typeRef));
    }

    @Override
    public <T> T convertValue(Object value, Class<T> clazz) {
        return JsonUtil.convertToBean(value, clazz);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> typeRef) {
        return JsonUtil.convertToBean(value, toTypeReference(typeRef));
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return JsonUtil.toJson(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return JsonUtil.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private <T> TypeReference<T> toTypeReference(TypeRef<T> typeRef) {
        return new TypeReference<>() {
            @Override
            public Type getType() {
                return typeRef.getType();
            }
        };
    }
}
