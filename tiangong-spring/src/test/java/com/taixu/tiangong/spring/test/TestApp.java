package com.taixu.tiangong.spring.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.taixu.tiangong.annotations.annotation.framework.enable.EnableAgentLoop;
import com.taixu.tiangong.spring.annotation.EnableTianGong;

/**
 * 集成测试应用：Spring 宿主 + 天工自举（不提供 API 服务）。
 */
@SpringBootApplication
@EnableTianGong
@EnableAgentLoop(contextClass = TestBridgeContext.class)
public class TestApp {

    public static void main(String[] args) {
        SpringApplication.run(TestApp.class, args);
    }
}
