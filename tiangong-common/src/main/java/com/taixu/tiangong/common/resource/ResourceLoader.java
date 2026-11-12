package com.taixu.tiangong.common.resource;

import java.io.InputStream;
import java.net.URL;

public class ResourceLoader {

    private static Class<?> mainClass;

    public static void load(Class<?> clazz) {
        mainClass = clazz;
    }

    public static URL getResource(String path) {
        return mainClass.getClassLoader().getResource(path);
    }

    public static InputStream getResourceAsStream(String path) {
        return mainClass.getClassLoader().getResourceAsStream(path);
    }

    public static boolean exists(String path) {
        return mainClass.getClassLoader().getResource(path) != null;
    }

}
