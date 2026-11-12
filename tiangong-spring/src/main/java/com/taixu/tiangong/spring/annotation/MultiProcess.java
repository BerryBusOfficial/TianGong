package com.taixu.tiangong.spring.annotation;

import java.lang.annotation.*;

/**
 * 声明"分进程部署模式"：天工与 Spring 各自独立进程，跨进程只能走服务寻址。<br>
 * <br>
 * <b>当前阶段（远程传输未实现）的语义</b>：与 {@link EnableTianGong}（同进程自举）、
 * 实例桥（{@code @TianGongWrite}/{@code @SpringWrite}）互斥 —— 标注即启动期报错，
 * 提示移除后使用同进程集成。<br>
 * <br>
 * 等远程寻址（{@code agent://} 之外的传输）落地后，本注解才真正开启分进程能力：
 * 服务桥按部署模式选择本地/远程通道，实例桥自动禁用。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface MultiProcess {
}
