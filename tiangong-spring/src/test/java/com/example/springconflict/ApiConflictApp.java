package com.example.springconflict;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableApi;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableAgentLoop;
import com.taixu.tiangong.spring.annotation.EnableTianGong;
import com.taixu.tiangong.spring.test.TestBridgeContext;

/**
 * 反例应用：Spring 宿主 + {@code @EnableApi}（API 禁用模式下必须启动期报错）。<br>
 * <br>
 * 特意放在 {@code com.example.*} 包：天工默认扫描 {@code org.wutong.tiangong}，
 * 若这个类在框架包下，正例应用（TianGongSpringBridgeTest）也会扫到它而误报。
 */
@SpringBootApplication
@EnableTianGong
@EnableApi
@EnableAgentLoop(contextClass = TestBridgeContext.class)
public class ApiConflictApp {

    public static void main(String[] args) {
        SpringApplication.run(ApiConflictApp.class, args);
    }
}
