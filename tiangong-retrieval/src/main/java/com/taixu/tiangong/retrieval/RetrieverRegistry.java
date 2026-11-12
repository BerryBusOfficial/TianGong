package com.taixu.tiangong.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 检索器注册表：按名寻址，让「图扩散 RAG / 向量召回 / 记忆事实」可以并存与插拔。<br>
 * 同名重复注册直接报错，不静默覆盖 —— 覆盖会让部署方以为自己在用 A，实际跑的是 B。
 */
public final class RetrieverRegistry {

    private final ConcurrentMap<String, Retriever> retrievers = new ConcurrentHashMap<>();

    public void register(Retriever retriever) {
        if (retriever == null || retriever.name() == null || retriever.name().isBlank()) {
            throw new IllegalArgumentException("Retriever 必须有非空 name");
        }
        Retriever previous = retrievers.putIfAbsent(retriever.name(), retriever);
        if (previous != null) {
            throw new IllegalStateException("重复注册 Retriever: " + retriever.name());
        }
    }

    public Optional<Retriever> get(String name) {
        return Optional.ofNullable(name == null ? null : retrievers.get(name));
    }

    /**
     * 按名取检索器；不存在时抛出带「已注册列表」的异常，避免只报一个名字难以排查。
     */
    public Retriever require(String name) {
        Retriever retriever = retrievers.get(name);
        if (retriever == null) {
            throw new RetrievalException("未注册的 Retriever: [" + name + "]，已注册: " + names());
        }
        return retriever;
    }

    public List<String> names() {
        return retrievers.keySet().stream().sorted().toList();
    }

    public Collection<Retriever> all() {
        return retrievers.values();
    }

    public int size() {
        return retrievers.size();
    }
}
