package com.taixu.tiangong.annotations.processor.registry;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;

public class BeanDefinitionRegistry implements Registry<BeanDefinition> {

    private final LinkedHashMap<String, BeanDefinition> beans = new LinkedHashMap<>();

    @Override
    public void register(BeanDefinition element) {
        beans.put(element.getBeanName(), element);
    }

    @Override
    public void unregister(String name) {
        beans.remove(name);
    }

    @Override
    public Optional<BeanDefinition> get(String name) {
        return Optional.ofNullable(beans.get(name));
    }

    @Override
    public Collection<BeanDefinition> values() {
        return beans.values();
    }

    @Override
    public boolean contains(String name) {
        return beans.containsKey(name);
    }

    @Override
    public int size() {
        return beans.size();
    }

    @Override
    public Iterator<BeanDefinition> iterator() {
        return beans.values().iterator();
    }
}
