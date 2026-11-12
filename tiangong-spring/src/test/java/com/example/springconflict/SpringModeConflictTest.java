package com.example.springconflict;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反例：Spring 宿主模式下 {@code @EnableApi} 必须启动期报错（API 由宿主提供，框架不再起服务）。
 */
class SpringModeConflictTest {

    @Test
    void enableApiIsRejectedInSpringHostMode() {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                        new SpringApplicationBuilder(ApiConflictApp.class)
                                .web(WebApplicationType.NONE)
                                .properties("spring.main.banner-mode=off", "logging.level.root=WARN")
                                .run(),
                "@EnableApi 在 Spring 宿主模式下必须让启动失败");

        assertTrue(messageChainContains(error, "API 禁用模式"),
                () -> "异常链里应包含 'API 禁用模式' 的报错，实际: " + error);
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
