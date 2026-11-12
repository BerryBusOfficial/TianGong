package org.wutong.tiangong.spring.annotation;

import java.lang.annotation.*;

/**
 * 方法 → 服务入口地址的映射（{@link ServiceAgent} 接口方法上使用）。<br>
 * <br>
 * <b>进程内调用，没有 HTTP 协议语义</b>：不需要 method / consumes 这类声明 ——
 * 参数序列化为 JSON 投递，返回值由对侧 {@code @ServiceExportNode} 回传。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EntranceCall {

    /**
     * 服务地址（必填），形如 {@code agent://quote/price}。<br>
     * 当前仅支持 {@code agent://} 前缀（同进程寻址）；远程寻址等传输落地后再扩展。
     */
    String url();

    /**
     * 目标工作流：同一 url 被多个工作流注册时<b>必填</b>。<br>
     * 消歧用参数形式表达，不把 workflowId 拼进 url（url 是稳定地址）。
     */
    String workflow() default "";
}
