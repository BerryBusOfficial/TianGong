package com.taixu.tiangong.annotations.scanner;

/**
 * 扫描收集到的 @Enable* 元数据。<br>
 * enableAnnotation：@Enable* 注解类；configurations：其 @FrameworkEnable value()；
 * userClass：标注了 @Enable* 的用户类；enabled：注解 value()（组合标注时为默认 true）。
 */
public record EnableMetadata(
        Class<?> enableAnnotation,
        Class<?>[] configurations,
        Class<?> userClass,
        boolean enabled
) {
}
