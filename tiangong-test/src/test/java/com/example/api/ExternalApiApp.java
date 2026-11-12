package com.example.api;

import org.wutong.tiangong.annotations.annotation.framework.enable.EnableApi;

/**
 * 模拟「框架包之外」的启动类：包名 {@code com.example.api} 不在 {@code org.wutong.tiangong} 下。<br>
 * <br>
 * 存在的意义是给 {@code @EnableApi} 的扫描路径提供<b>对照组</b>：
 * 以前开关判定用一段硬编码 {@code acceptPackages("org.wutong.tiangong")} 的 ClassGraph，
 * 这类业务包的启动类一个都扫不到 → API 层静默关闭。
 */
@EnableApi
public class ExternalApiApp {
}
