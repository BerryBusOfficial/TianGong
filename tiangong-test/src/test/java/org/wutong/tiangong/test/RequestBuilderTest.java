package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.llm.builder.RequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * URL 拼接的规则验证。<br>
 * 重点是「base_url 已带 /v1」这一支：OpenAI 生态里 {@code https://host/v1} 是标准写法，
 * 而端点本身也以 {@code /v1} 开头，纯拼接会得到 {@code /v1/v1/...} 然后 404 ——
 * 且服务端只回一个 404，不会告诉你「前缀重复了」，排查成本很高。<br>
 * 另外顺带钉住：这个去重只对 /v1 生效，不能误伤 Deepseek / Ollama 这些端点不带 /v1 的。
 */
class RequestBuilderTest {

    /** GET + 无 body：body 为 null 时直接 build 会在 ofString(null) 上 NPE */
    private static String join(String baseUrl, String endpoint) {
        return new RequestBuilder()
                .uri(baseUrl, endpoint)
                .method(RequestBuilder.Method.GET)
                .nonBody()
                .build()
                .uri()
                .toString();
    }

    @Test
    void collapsesDuplicateVersionPrefixWhenBaseUrlAlreadyHasIt() {
        // 用户给的正是这种写法
        assertEquals("https://api.agnes-ai.cn/v1/chat/completions",
                join("https://api.agnes-ai.cn/v1", "/v1/chat/completions"));
        assertEquals("https://api.agnes-ai.cn/v1/models",
                join("https://api.agnes-ai.cn/v1", "/v1/models"));
        // 末尾带斜杠也一样处理
        assertEquals("https://api.agnes-ai.cn/v1/chat/completions",
                join("https://api.agnes-ai.cn/v1/", "/v1/chat/completions"));
    }

    @Test
    void keepsVersionPrefixWhenBaseUrlHasNone() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                join("https://api.openai.com", "/v1/chat/completions"));
    }

    @Test
    void leavesOtherProvidersUntouched() {
        // Deepseek 端点不带 /v1，base 也不带 —— 行为必须与改动前一致
        assertEquals("https://api.deepseek.com/chat/completions",
                join("https://api.deepseek.com", "/chat/completions"));
        assertEquals("https://api.deepseek.com/models",
                join("https://api.deepseek.com", "/models"));
        // Ollama 同上
        assertEquals("http://localhost:11434/api/chat",
                join("http://localhost:11434", "/api/chat"));
        // 只认 /v1：其它版本前缀不去重，避免误伤用 /v2 的服务
        assertEquals("https://host/v2/v2/chat",
                join("https://host/v2", "/v2/chat"));
    }

    @Test
    void normalizesSlashBoundaries() {
        assertEquals("https://host/api/chat", join("https://host/", "/api/chat"));
        assertEquals("https://host/api/chat", join("https://host", "/api/chat"));
        assertEquals("https://host/api/chat", join("https://host", "api/chat"));
    }

    /**
     * models 接口是「GET + 空 body」：以前 noBody 会给 method() 传 null publisher 直接 NPE，
     * 也就是<b>任何一个 provider 的 models() 一调就崩</b>。这里钉住它必须能构建。
     */
    @Test
    void getWithEmptyBodyIsBuildable() {
        var request = new RequestBuilder()
                .uri("https://api.agnes-ai.cn/v1", "/v1/models")
                .method(RequestBuilder.Method.GET)
                .body(Map.of())                    // 空 map → 走 nonBody() 分支
                .auth("sk-test")
                .build();

        assertEquals("https://api.agnes-ai.cn/v1/models", request.uri().toString());
        assertEquals("GET", request.method());
        assertEquals(0, request.bodyPublisher().orElseThrow().contentLength());
    }

    @Test
    void buildsRequestWithAuthHeader() {
        var request = new RequestBuilder()
                .uri("https://api.agnes-ai.cn/v1", "/v1/chat/completions")
                .method(RequestBuilder.Method.POST)
                .body("{}")
                .auth("sk-test")
                .build();

        assertEquals("https://api.agnes-ai.cn/v1/chat/completions", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer sk-test", request.headers().firstValue("Authorization").orElseThrow());
    }
}
