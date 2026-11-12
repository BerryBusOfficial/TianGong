package org.wutong.tiangong.user.trace;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.core.trace.TraceFieldEvent;
import org.wutong.tiangong.core.trace.TraceHandler;
import org.wutong.tiangong.user.util.StringUtil;

@Slf4j
@Component("node")
public class NodeTraceHandler implements TraceHandler {

    @Override
    public void onFieldWrite(TraceFieldEvent event) {

        log.info(
                "{} 字段：{} -> {}", event.fieldName(),
                StringUtil.subString(event.oldValue(), 0, 50),
                StringUtil.subString(event.newValue(), 0, 50)
        );
    }

}
