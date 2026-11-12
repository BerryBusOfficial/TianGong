package com.example.multiprocess;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反例：分进程模式（{@code @MultiProcess}）当前必须启动期报错（远程传输未实现）。
 */
class MultiProcessConflictTest {

    @Test
    void multiProcessIsRejectedUntilRemoteTransportLands() {
        // Spring 会把 ImportSelector 的异常包一层（BeanDefinitionStoreException），
        // 这里断言消息链（错误语义必须完整可见）
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                        new SpringApplicationBuilder(MultiProcessApp.class)
                                .web(WebApplicationType.NONE)
                                .properties("spring.main.banner-mode=off", "logging.level.root=WARN")
                                .run(),
                "@MultiProcess 与 @EnableTianGong 互斥，必须启动期报错");

        assertTrue(messageChainContains(error, "分进程"), () -> "异常应说明分进程模式: " + error);
        assertTrue(messageChainContains(error, "@MultiProcess"), () -> "异常应点名 @MultiProcess: " + error);
    }

    private static boolean messageChainContains(Throwable error, String text) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
