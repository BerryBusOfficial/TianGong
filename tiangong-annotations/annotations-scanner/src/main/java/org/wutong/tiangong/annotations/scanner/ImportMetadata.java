package org.wutong.tiangong.annotations.scanner;

/**
 * 扫描收集到的 @Import 元数据。<br>
 * userClass：标注 @Import 的用户类；imports：要导入注册的类列表。
 */
public record ImportMetadata(
        Class<?> userClass,
        Class<?>[] imports
) {
}
