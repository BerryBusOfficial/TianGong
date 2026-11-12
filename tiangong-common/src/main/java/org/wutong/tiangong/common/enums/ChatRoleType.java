package org.wutong.tiangong.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChatRoleType {

    USER("User", "user", "用户"),
    ASSISTANT("Assistant", "assistant", "智能体"),
    SYSTEM("System", "system", "系统"),
    TOOL("TOOL", "tool", "工具"),
    ;

    private final String name;
    @JsonValue
    private final String value;
    private final String description;

}
