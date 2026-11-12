package com.taixu.tiangong.spring;

import java.util.List;

/**
 * 启动类登记：{@code @EnableTianGong} 的 ImportSelector 在解析注解时记录，
 * 后续（BDRPP / 自举）从这里取启动类与额外扫描包。<br>
 * <br>
 * <b>为什么需要它</b>：{@code ResourceLoader}（@Inject RESOURCE 的资源定位）需要启动类
 * 的 ClassLoader；扫描包默认取启动类所在包。这两件事都必须知道"哪个类标了
 * {@code @EnableTianGong}"，而 ImportSelector 的元数据里正好有。
 * 与框架的 ContextHolder 同类：静态登记，最后装配者生效。
 */
final class TianGongAppInfo {

    private static volatile Class<?> appClass;
    private static volatile List<String> packages = List.of();

    private TianGongAppInfo() {
    }

    static void record(Class<?> appClass, List<String> packages) {
        TianGongAppInfo.appClass = appClass;
        TianGongAppInfo.packages = packages == null ? List.of() : List.copyOf(packages);
    }

    /** 启动类；未登记时为 null（如手工 new 配置类的场景）。 */
    static Class<?> appClass() {
        return appClass;
    }

    /** {@code @EnableTianGong(packages = ...)} 声明的额外扫描包。 */
    static List<String> packages() {
        return packages;
    }
}
