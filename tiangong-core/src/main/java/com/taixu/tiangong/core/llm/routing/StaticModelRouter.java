package com.taixu.tiangong.core.llm.routing;

import java.util.Map;

/**
 * 静态路由表：任务名 → 候选链，未命中的任务走默认链。<br>
 * 这是最小实现；灰度/按租户/按成本的路由只要换一个 {@link ModelRouter} 实现即可。
 */
public final class StaticModelRouter implements ModelRouter {

    private final Map<String, ModelRoute> routes;
    private final ModelRoute defaultRoute;

    private StaticModelRouter(Map<String, ModelRoute> routes, ModelRoute defaultRoute) {
        this.routes = Map.copyOf(routes);
        this.defaultRoute = defaultRoute;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelRoute route(String task, com.taixu.tiangong.common.agent.call.AgentCallContext context) {
        ModelRoute route = task == null ? null : routes.get(task);
        if (route != null) {
            return route;
        }
        if (defaultRoute != null) {
            return defaultRoute;
        }
        throw new RoutingException("没有任务 [" + task + "] 的路由，也没有默认路由；已配置: " + routes.keySet());
    }

    public static final class Builder {

        private final java.util.Map<String, ModelRoute> routes = new java.util.LinkedHashMap<>();
        private ModelRoute defaultRoute;

        public Builder route(ModelRoute route) {
            routes.put(route.task(), route);
            return this;
        }

        public Builder route(String task, String... candidates) {
            return route(ModelRoute.of(task, candidates));
        }

        public Builder defaultRoute(ModelRoute route) {
            this.defaultRoute = route;
            return this;
        }

        public Builder defaultRoute(String... candidates) {
            return defaultRoute(ModelRoute.of("default", candidates));
        }

        public StaticModelRouter build() {
            return new StaticModelRouter(routes, defaultRoute);
        }
    }
}
