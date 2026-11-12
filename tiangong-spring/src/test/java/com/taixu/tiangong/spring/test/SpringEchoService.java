package com.taixu.tiangong.spring.test;

import org.springframework.stereotype.Component;

/**
 * Spring 侧服务：供天工 Bean 通过 {@code @SpringWrite} 写入（实例桥 Spring→天工 方向）。
 */
@Component
public class SpringEchoService {

    public String decorate(String text) {
        return "spring:" + text;
    }
}
