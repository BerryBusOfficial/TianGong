package com.taixu.tiangong.core.llm.routing;

import java.util.List;

/**
 * 一条任务的路由：按顺序尝试候选模型，前者失败/空回答时降级到后者。<br>
 * <br>
 * 与「按 provider 写死模型」的区别：路由是<b>任务语义</b>（report / chat / classify），
 * 候选列表是部署期可替换的实现（flash → pro → 本地 ollama）。
 *
 * @param task               任务名（路由键）
 * @param candidates         候选客户端/模型配置名，按优先级排列
 * @param maxReasoningTokens 推理 token 软上限（0 = 不限制；当前作为路由元数据，供执行器/观测使用）
 * @param maxOutputTokens    输出 token 上限（0 = 不限制；由请求工厂决定是否落到 provider 参数）
 */
public record ModelRoute(
        String task,
        List<String> candidates,
        int maxReasoningTokens,
        int maxOutputTokens
) {

    public ModelRoute {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("路由任务名不能为空");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("路由 [" + task + "] 至少需要一个候选模型");
        }
        candidates = candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("路由 [" + task + "] 的候选模型全部为空");
        }
        maxReasoningTokens = Math.max(0, maxReasoningTokens);
        maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    public static ModelRoute of(String task, String... candidates) {
        return new ModelRoute(task, List.of(candidates), 0, 0);
    }

    public String primary() {
        return candidates.getFirst();
    }

    public List<String> fallbacks() {
        return candidates.size() <= 1 ? List.of() : candidates.subList(1, candidates.size());
    }
}
