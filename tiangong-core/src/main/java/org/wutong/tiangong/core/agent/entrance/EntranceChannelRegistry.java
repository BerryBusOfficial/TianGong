package org.wutong.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 入口通道注册表：按名字持有 {@link EntranceChannel}。<br>
 * <br>
 * <h2>为什么需要它</h2>
 * 之前 {@code AgentLoopExecutor} 把三种内置通道（STDIN / HTTP / 审批）当成
 * 硬编码字段，注解上的 {@code channel()} 名<b>根本没被读</b> —— 于是
 * {@code @ApproveNode(channel = "...")} 是个写了没用的属性：文档说"可按名替换为
 * 自己的通道实现"，代码里从不按名查找。本类就是把这条线接上。<br>
 * <br>
 * <h2>约定</h2>
 * <ul>
 *     <li><b>内置通道在构造时注册</b>：{@code approval} / {@code stdin} / {@code http}
 *         （HTTP 因依赖路由注册表，懒加载时补注册）。</li>
 *     <li><b>同名为替换</b>：这才有"用我自己的审批通道替掉默认那个"的可能。
 *         替换会打 WARN —— 已经阻塞在旧通道上的工作流<b>不会</b>被迁移过去，
 *         这是调用方要自己想清楚的事。</li>
 *     <li><b>找不到就报错，不静默回退</b>：节点上写了 {@code channel = "foo"} 却没注册，
 *         就是配置错误。静默退回默认通道会让"我以为换成自己的了"变成最难受的那类 bug。</li>
 * </ul>
 */
@Slf4j
public class EntranceChannelRegistry {

    private final Map<String, EntranceChannel<?>> byName = new ConcurrentHashMap<>();

    /**
     * 注册（或替换）一个通道。<br>
     * 同名重复注册视为替换，打 WARN 并说明"已在等待的工作流不会被迁移"。
     *
     * @throws IllegalArgumentException 通道为 null 或名字为空
     */
    public void register(EntranceChannel<?> channel) {
        if (channel == null) {
            throw new IllegalArgumentException("入口通道不能为 null");
        }
        String name = channel.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("入口通道的 name() 不能为空：" + channel.getClass().getName());
        }
        EntranceChannel<?> previous = byName.put(name, channel);
        if (previous != null && previous != channel) {
            log.warn("入口通道 [{}] 被替换：{} -> {}；"
                            + "已经阻塞在旧通道上等待的工作流不会被迁移到新通道",
                    name, previous.getClass().getSimpleName(), channel.getClass().getSimpleName());
        } else if (previous == null) {
            log.debug("入口通道已注册: {} -> {}", name, channel.getClass().getSimpleName());
        }
    }

    /**
     * 按名取通道；不存在返回 {@code null}（由调用方决定是报错还是回退）。
     */
    public EntranceChannel<?> get(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(name);
    }

    public boolean contains(String name) {
        return get(name) != null;
    }

    /** 已注册的名字（排序后，便于报错信息稳定可读）。 */
    public List<String> names() {
        List<String> all = new ArrayList<>(byName.keySet());
        Collections.sort(all);
        return all;
    }

    /** 已注册通道数。 */
    public int size() {
        return byName.size();
    }

    @Override
    public String toString() {
        return byName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().getClass().getSimpleName())
                .collect(Collectors.joining(", ", "EntranceChannels[", "]"));
    }
}
