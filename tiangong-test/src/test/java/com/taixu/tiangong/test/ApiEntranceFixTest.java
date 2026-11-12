package com.taixu.tiangong.test;

import com.taixu.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableApi;
import com.taixu.tiangong.annotations.scanner.EnableMetadata;
import com.taixu.tiangong.annotations.scanner.TianGongScanner;
import com.taixu.tiangong.common.enums.RequestMethod;
import com.taixu.tiangong.core.api.ApiHttpServer;
import com.taixu.tiangong.core.api.entrance.EntranceRequest;
import com.taixu.tiangong.core.api.entrance.EntranceResponse;
import com.taixu.tiangong.core.api.entrance.EntranceRoute;
import com.taixu.tiangong.core.api.entrance.EntranceRouteRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三处 API 入口缺陷的回归测试。<br>
 * <br>
 * 每条都配了可观测依据或对照组，不用"跑一遍没报错"当通过：
 * <ol>
 *     <li>{@code @EnableApi} 在<b>业务包</b>下必须能被发现（并对照证明旧的硬编码扫描扫不到）</li>
 *     <li>{@code concurrencyLimit} 默认必须是「不限」，否则长任务把并发静默打成 429</li>
 *     <li>响应超时必须<b>作废响应 Future</b>，而不是静默地让工作流白跑到底</li>
 * </ol>
 */
class ApiEntranceFixTest {

    private ApiHttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ==================== 1. @EnableApi 的扫描路径 ====================

    @Test
    void enableApiIsDiscoverableInBusinessPackage() {
        TianGongScanner scanner = TianGongScanner.load(
                new BeanDefinitionRegistry(),
                s -> s.acceptPackages("com.example.api"));

        List<EnableMetadata> apiMetas = scanner.getEnableMetadata().stream()
                .filter(m -> m.enableAnnotation() == EnableApi.class)
                .toList();

        assertFalse(apiMetas.isEmpty(), "扫描器必须收集到 @EnableApi 元数据（开关判定的唯一输入）");
        assertTrue(apiMetas.stream().anyMatch(m ->
                        m.userClass() == com.example.api.ExternalApiApp.class && m.enabled()),
                "业务包启动类上的 @EnableApi 必须被发现且为开启，实际: " + apiMetas);
    }

    @Test
    void hardcodedFrameworkOnlyScanMissesBusinessPackage() {
        // 对照组：证明"只扫 org.wutong.tiangong"这条老路径确实会漏掉业务包。
        // 没有这条，'改成走扫描器'的必要性就只是推测。
        try (ScanResult scan = new ClassGraph()
                .acceptPackages("org.wutong.tiangong")
                .enableAllInfo()
                .scan()) {
            boolean found = scan.getClassesWithAnnotation(EnableApi.class.getName())
                    .stream()
                    .anyMatch(info -> info.getName().equals(com.example.api.ExternalApiApp.class.getName()));
            assertFalse(found,
                    "硬编码只扫框架包时，业务包的 @EnableApi 应当扫不到 —— "
                            + "这正是以前 HTTP 入口静默失效的根因");
        }
    }

    // ==================== 2. 并发上限默认值 ====================

    @Test
    void concurrencyLimitDefaultsToUnlimited() throws Exception {
        var method = DefaultsWorkflow.class.getDeclaredMethod("entrance", TestContext.class);
        ApiEntranceNode ann = method.getAnnotation(ApiEntranceNode.class);

        assertNotNull(ann);
        assertEquals(0, ann.concurrencyLimit(),
                "默认必须是 0（不额外限制）：默认 1 会让长任务的后续请求全部 429，"
                        + "而真正的闸门是全局并发槽");
    }

    @Test
    void zeroConcurrencyLimitMeansUnlimited() {
        var route = new EntranceRoute("wf-unlimited", "task",
                new RequestMethod[]{RequestMethod.GET},
                new String[]{}, new String[]{}, new String[]{}, new String[]{},
                0, null);
        for (int i = 0; i < 50; i++) {
            assertTrue(route.tryAcquireConcurrency(),
                    "concurrencyLimit=0 表示不额外限制，第 " + (i + 1) + " 次也应放行");
        }
    }

    // ==================== 3. 超时作废响应 ====================

    @Test
    void timeoutCancelsResponseFuture() throws Exception {
        EntranceRouteRegistry registry = new EntranceRouteRegistry();
        EntranceRoute route = new EntranceRoute("wf-timeout", "slow",
                new RequestMethod[]{RequestMethod.GET},
                new String[]{}, new String[]{}, new String[]{}, new String[]{},
                0, null);
        registry.register(route);

        server = new ApiHttpServer(registry, "127.0.0.1", 0, 1);   // 1 秒超时
        server.start();
        int port = server.getBoundPort();
        assertTrue(port > 0);

        HttpClient client = HttpClient.newHttpClient();
        var httpFuture = client.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/wf-timeout/slow"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // 从路由队列取出请求（模拟工作流取到它，但不回传 → 触发超时）
        EntranceRequest request = route.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "请求应被投递到路由队列");

        HttpResponse<String> response = httpFuture.get(15, TimeUnit.SECONDS);
        assertEquals(504, response.statusCode(), "未在超时内响应应返回 504");

        assertTrue(request.responseFuture().isCancelled(),
                "超时必须主动作废响应 Future：不 cancel 的话工作流会一路跑到 EXPORT，"
                        + "把结果塞进无人接收的 Future，全程零日志");

        // 迟到的 EXPORT 回传应当失败，而不是静默"成功"
        assertFalse(request.responseFuture().complete(
                        new EntranceResponse(200, "迟到的结果", "application/json")),
                "已作废的 Future 再 complete 必须返回 false，让执行器能识别并记一笔");
    }

    // ==================== 测试用工作流 ====================

    /** 只用默认值的入口节点：用于读取注解默认值 */
    static class DefaultsWorkflow {
        @ApiEntranceNode(route = "/default")
        public void entrance(TestContext ctx) {
        }
    }
}
