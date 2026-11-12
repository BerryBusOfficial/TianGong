package org.wutong.tiangong.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import org.wutong.tiangong.common.handler.UnknownPropertyWarningHandler;

import java.util.Iterator;
import java.util.Map;

public class JsonUtil {

    @Getter
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setDefaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)
            )
            .addHandler(new UnknownPropertyWarningHandler());

    /**
     * 外部入口请求体专用 mapper。<br>
     * <b>为什么不能复用 {@link #JSON_MAPPER}</b>：那个 mapper 全局设了 SNAKE_CASE，
     * 于是客户端按 Java 字段名发 {@code {"repoPath": "..."}} 时，mapper 期望的是
     * {@code repo_path} —— 字段被静默忽略、取到 null，接口报 500 而日志里什么都没有。
     * 这个 mapper 不设命名策略、大小写不敏感，配合 {@link #normalizeKeys} 两种写法都收。
     */
    private static final ObjectMapper BODY_MAPPER = JsonMapper.builder()
            // ⚠ 必须 findAndAddModules()：jackson-module-parameter-names 是 record 反序列化的
            // 前提（没有它就找不到构造器参数名）。漏了这一行时，入口参数类型是 record 的
            // 场景全部失败 —— 审批决定的 ApprovalDecision 就是 record，一改就炸。
            .findAndAddModules()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addHandler(new UnknownPropertyWarningHandler())
            .build();

    public static ObjectNode createObjectNode() {
        return JSON_MAPPER.createObjectNode();
    }

    /**
     * 共享 mapper 实例（只读使用）。<br>
     * <b>不要修改它</b>——它是 LLM 请求构建等链路共用的配置。
     * 需要不同配置时先 {@code copy()} 再改（参见 {@code JsonContextSnapshotter}）。
     */
    public static ObjectMapper mapper() {
        return JSON_MAPPER;
    }

    /**
     * 入口请求体 mapper：解析外部传入的 body，不做命名策略转换。
     */
    public static ObjectMapper bodyMapper() {
        return BODY_MAPPER;
    }

    public static Map<String, Object> convertToMap(Object object) {
        return JSON_MAPPER.convertValue(object, new TypeReference<>() {
        });
    }

    public static <T> T convertToBean(Object object, Class<T> clazz) {
        return JSON_MAPPER.convertValue(object, clazz);
    }

    public static <T> T convertToBean(Object object, TypeReference<T> typeReference) {
        return JSON_MAPPER.convertValue(object, typeReference);
    }

    public static String toJson(Object object) throws JsonProcessingException {
        return JSON_MAPPER.writeValueAsString(object);
    }

    public static <T> T fromJson(String json, Class<T> clazz) throws JsonProcessingException {
        return JSON_MAPPER.readValue(json, clazz);
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) throws JsonProcessingException {
        return JSON_MAPPER.readValue(json, typeReference);
    }

    /**
     * 解析外部入口（HTTP / STDIN / Webhook）的请求体。<br>
     * <b>同时接受 camelCase 与 snake_case</b>：先把所有 key 统一成 camelCase 再绑定，
     * 于是 {@code repoPath} 与 {@code repo_path} 都能落到同一个字段上。
     * 用 @{@code @JsonAlias} 手工补别名的做法只能救一个字段，这里一次性救全部。
     */
    public static <T> T fromBody(String json, Class<T> clazz) throws JsonProcessingException {
        JsonNode tree = normalizeKeys(BODY_MAPPER.readTree(json));
        return BODY_MAPPER.treeToValue(tree, clazz);
    }

    /**
     * 同 {@link #fromBody}，用于多参数入口方法（先摊平成 Map，再按形参名取值）。
     */
    public static Map<String, Object> bodyToMap(String json) throws JsonProcessingException {
        JsonNode tree = normalizeKeys(BODY_MAPPER.readTree(json));
        return BODY_MAPPER.convertValue(tree, new TypeReference<>() {
        });
    }

    /**
     * 递归把 JSON 里所有 key 从 snake_case 归一化成 camelCase。
     * 已经是 camelCase 的 key 原样返回（不含下划线时直接短路）。
     */
    private static JsonNode normalizeKeys(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = BODY_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                out.set(toCamel(entry.getKey()), normalizeKeys(entry.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = BODY_MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(normalizeKeys(child));
            }
            return out;
        }
        return node;
    }

    private static String toCamel(String key) {
        if (key == null || key.indexOf('_') < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        boolean upperNext = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }

}
