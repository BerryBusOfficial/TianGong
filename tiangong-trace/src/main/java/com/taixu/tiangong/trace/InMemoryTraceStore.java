package com.taixu.tiangong.trace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存 trace store：单机/测试用，进程退出即丢失。事件按写入顺序保留，便于回放。
 */
public final class InMemoryTraceStore implements TraceStore {

    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(LlmTraceRecord record) {
        if (record != null) {
            events.add(record);
        }
    }

    @Override
    public void record(ToolTraceRecord record) {
        if (record != null) {
            events.add(record);
        }
    }

    @Override
    public List<TraceEvent> timeline(String runId) {
        if (runId == null) {
            return List.of();
        }
        return events.stream()
                .filter(event -> runId.equals(event.runId()))
                .toList();
    }

    public Set<String> runs() {
        Set<String> runs = new LinkedHashSet<>();
        for (TraceEvent event : events) {
            if (event.runId() != null) {
                runs.add(event.runId());
            }
        }
        return runs;
    }

    public List<TraceEvent> all() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
