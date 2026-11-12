package org.wutong.tiangong.user.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API 出口的响应体。<br>
 * 除了回复本身，把<b>状态一起回传</b>：调用方（未来的桌宠界面）要拿它驱动表情与动画，
 * 否则前端还得再发一个请求问「你现在什么心情」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PetReply {

    private String reply;
    private String mood;
    private int energy;
    private int affinity;
    private long turns;
    private String sessionId;
}
