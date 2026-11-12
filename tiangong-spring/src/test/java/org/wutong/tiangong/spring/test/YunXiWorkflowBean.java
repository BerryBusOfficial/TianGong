package org.wutong.tiangong.spring.test;

import org.wutong.tiangong.annotations.annotation.framework.Component;

/**
 * 天工侧普通 Bean：供 Spring Bean 通过 {@code @TianGongWrite} 写入
 * （实例桥 天工→Spring 方向；具体类 → 占位 + 回填）。
 */
@Component
public class YunXiWorkflowBean {

    public String id() {
        return "tiangong-bean";
    }
}
