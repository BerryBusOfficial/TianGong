package com.taixu.tiangong.common.entity;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

public class SomeTypeReference {

    public final static MapTypeReference MAP_STRING_OBJECT = new MapTypeReference();
    public final static ListTypeReference LIST_OBJECT = new ListTypeReference();

    public static class MapTypeReference extends TypeReference<Map<String, Object>> {
    }

    public static class ListTypeReference extends TypeReference<List<Object>> {
    }

}
