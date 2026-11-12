package org.wutong.tiangong.user.model;

import lombok.Data;

/**
 * API 入口的请求体。<br>
 * <br>
 * 用类不用 record：Jackson 反序列化 record 需要 {@code ParameterNamesModule}
 * （且要 {@code -parameters} 编译），框架共享的 mapper 未必带；
 * 带无参构造 + setter 的普通类在任何配置下都能解析。
 */
@Data
public class PetChatRequest {

    /** 用户说的话 */
    private String message;
}
