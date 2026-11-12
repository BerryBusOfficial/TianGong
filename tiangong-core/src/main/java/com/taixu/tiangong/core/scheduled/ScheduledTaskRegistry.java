package com.taixu.tiangong.core.scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 定时任务注册表：收集所有 @Scheduled 方法对应的任务定义。
 */
public class ScheduledTaskRegistry {

    private final List<ScheduledTaskDefinition> tasks = new CopyOnWriteArrayList<>();

    public void register(ScheduledTaskDefinition task) {
        tasks.add(task);
    }

    public List<ScheduledTaskDefinition> tasks() {
        return new ArrayList<>(tasks);
    }

    public int size() {
        return tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}
