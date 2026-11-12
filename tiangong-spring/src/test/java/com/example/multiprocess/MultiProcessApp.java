package com.example.multiprocess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.wutong.tiangong.spring.annotation.EnableTianGong;
import org.wutong.tiangong.spring.annotation.MultiProcess;

/**
 * 反例应用：分进程模式（{@code @MultiProcess}）+ 同进程自举（{@code @EnableTianGong}）
 * —— 远程传输未实现前必须启动期报错。
 */
@SpringBootApplication
@EnableTianGong
@MultiProcess
public class MultiProcessApp {

    public static void main(String[] args) {
        SpringApplication.run(MultiProcessApp.class, args);
    }
}
