package org.wutong.tiangong.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import org.wutong.tiangong.common.enums.RequestMethod;
import org.wutong.tiangong.core.api.ApiHttpServer;
import org.wutong.tiangong.core.api.entrance.EntranceRequest;
import org.wutong.tiangong.core.api.entrance.EntranceRouteRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API 层测试：EntranceNode/ExportNode 动态路由 + HTTP 端到端。<br>
 * <br>
 * 测试流程：创建 EntranceRouteRegistry + ApiHttpServer → 注册工作流 →
 * 工作流执行到 EntranceNode → 阻塞等待请求 → HTTP 请求 → fork 执行 → ExportNode 回传响应。
 */
class ApiLayerTest {

    private ApiHttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    void endToEndGetRequest() throws Exception {
        EntranceRouteRegistry registry = new EntranceRouteRegistry();
        server = new ApiHttpServer(registry, "127.0.0.1", 0);
        server.start();
        int port = server.getBoundPort();
        assertTrue(port > 0, "服务器应绑定到有效端口");

        // 模拟：手动注册一个 EntranceRoute 并提交请求
        // 完整端到端测试需要工作流执行器，这里验证 HTTP 层基本可用
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/nonexistent"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), "未注册路由应返回 404");
    }

    @Test
    void routeRegistryRegisterAndFind() {
        EntranceRouteRegistry registry = new EntranceRouteRegistry();
        // 手动构造一个 EntranceRoute 进行测试
        var route = new org.wutong.tiangong.core.api.entrance.EntranceRoute(
                "wf-001", "submit",
                new RequestMethod[]{RequestMethod.POST},
                new String[]{}, new String[]{}, new String[]{}, new String[]{},
                1, null
        );
        registry.register(route);
        assertEquals(1, registry.size());

        // 按方法+路径查找
        var found = registry.find(RequestMethod.POST, "/wf-001/submit");
        assertTrue(found.isPresent(), "应找到注册的路由");

        // GET 不匹配 POST 路由
        var notFound = registry.find(RequestMethod.GET, "/wf-001/submit");
        assertTrue(notFound.isEmpty(), "GET 不应匹配 POST 路由");

        // 注销工作流
        registry.unregisterWorkflow("wf-001");
        assertEquals(0, registry.size(), "注销后应清空");
    }

    @Test
    void routeConcurrencyLimit() {
        var route = new org.wutong.tiangong.core.api.entrance.EntranceRoute(
                "wf-002", "task",
                new RequestMethod[]{RequestMethod.GET},
                new String[]{}, new String[]{}, new String[]{}, new String[]{},
                2, null
        );
        assertTrue(route.tryAcquireConcurrency(), "第一个应获取成功");
        assertTrue(route.tryAcquireConcurrency(), "第二个应获取成功");
        assertFalse(route.tryAcquireConcurrency(), "第三个应被拒绝（超过上限 2）");
        route.releaseConcurrency();
        assertTrue(route.tryAcquireConcurrency(), "释放后应可再次获取");
    }

    @Test
    void routeCloseRejectsPendingRequests() throws Exception {
        var route = new org.wutong.tiangong.core.api.entrance.EntranceRoute(
                "wf-003", "close",
                new RequestMethod[]{RequestMethod.GET},
                new String[]{}, new String[]{}, new String[]{}, new String[]{},
                0, null
        );
        var request = EntranceRequest.get("/wf-003/close", null);
        route.submit(request);
        route.close();
        var response = request.responseFuture().get();
        assertEquals(503, response.status(), "路由关闭后待处理请求应返回 503");
    }

    @Test
    void apiEntranceNodeAnnotationMetadata() throws Exception {
        // 验证 @ApiEntranceNode 注解属性正确解析
        var method = TestWorkflow.class.getDeclaredMethod("entrance",
                TestContext.class, String.class);
        ApiEntranceNode ann = method.getAnnotation(ApiEntranceNode.class);
        assertNotNull(ann, "@ApiEntranceNode 应存在");
        assertEquals("/submit", ann.route());
        assertArrayEquals(new RequestMethod[]{RequestMethod.POST}, ann.method());
        assertEquals(5, ann.concurrencyLimit());
    }

    @Test
    void apiExportNodeAnnotationMetadata() throws Exception {
        var method = TestWorkflow.class.getDeclaredMethod("export", TestContext.class);
        ApiExportNode ann = method.getAnnotation(ApiExportNode.class);
        assertNotNull(ann, "@ApiExportNode 应存在");
        assertEquals(201, ann.status());
    }

    // ==================== 测试用工作流 ====================

    static class TestWorkflow {
        @AgentNode(type = org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType.START)
        @NodeNext("entrance")
        public void start(TestContext ctx) {
            ctx.steps.add("start");
        }

        @ApiEntranceNode(route = "/submit", method = RequestMethod.POST, concurrencyLimit = 5)
        @NodeNext("process")
        public void entrance(TestContext ctx, String input) {
            ctx.steps.add("entrance:" + input);
        }

        @AgentNode
        @NodeNext("export")
        public void process(TestContext ctx) {
            ctx.steps.add("process");
        }

        @ApiExportNode(status = 201)
        @NodeNext("end")
        public String export(TestContext ctx) {
            ctx.steps.add("export");
            return "{\"result\":\"ok\"}";
        }

        @AgentNode(type = org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType.END)
        public void end(TestContext ctx) {
            ctx.steps.add("end");
        }
    }
}
