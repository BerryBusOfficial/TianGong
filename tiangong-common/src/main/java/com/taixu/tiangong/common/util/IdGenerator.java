package com.taixu.tiangong.common.util;

import com.taixu.tiangong.common.enums.Namespace;
import com.taixu.tiangong.common.enums.ResourceType;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.util.UUID;

/**
 * 云熙统一ID生成器
 * <p>
 * ID格式:
 * <p>
 * namespace:type:identifier
 * <p>
 * 示例:
 * <p>
 * llm:response:a82f91c3
 * memory:fact:91bc21aa
 * agent:session:7fd82abc
 *
 */
public final class IdGenerator {


    private IdGenerator() {
    }


    /**
     * 生成唯一ID
     *
     * @param namespace 命名空间
     * @param type      资源类型
     * @param length    随机ID长度
     * @return 唯一ID
     */
    public static String generate(
            Namespace namespace,
            ResourceType type,
            int length
    ) {

        if (length <= 0) {
            throw new SystemRuntimeException("ID长度必须大于零");
        }


        return "%s:%s:%s".formatted(
                namespace.getValue(),
                type.getValue(),
                random(length)
        );
    }


    /**
     * 默认生成8位随机ID
     */
    public static String generate(
            Namespace namespace,
            ResourceType type
    ) {
        return generate(namespace, type, 8);
    }


    private static String random(int length) {

        String uuid = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .toLowerCase();

        return uuid.substring(0, Math.min(length, uuid.length()));
    }
}