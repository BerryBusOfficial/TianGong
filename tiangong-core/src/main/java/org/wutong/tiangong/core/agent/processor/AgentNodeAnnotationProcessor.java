package org.wutong.tiangong.core.agent.processor;

import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.processor.NodeProcessor;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;

import java.util.List;

/**
 * AgentNode 注解处理器入口：持有所有 {@link NodeProcessor}，<br>
 * 对每个 Bean 依次调用各处理器的 process()，由处理器按 support(type) 自行过滤。<br>
 * 所有结构校验（返回类型、next 数量）在注册阶段完成，抛出的框架异常不被 @ListenException / advice 捕获。
 */
public class AgentNodeAnnotationProcessor implements AnnotationProcessor {

    private final List<NodeProcessor> processors;

    public AgentNodeAnnotationProcessor(AgentNodeRegistry registry) {
        // 注意：CommonNodeProcessor.support() 是列举式（START/COMMON/END/ENTRANCE/MERGE），
        // 新增 NodeType 不会自动被它覆盖 —— 必须在此登记对应 Processor，
        // 否则该类型的节点根本不会被注册，且没有任何报错。
        this.processors = List.of(
                new CommonNodeProcessor(registry),
                new BranchNodeProcessor(registry),
                new ParallelNodeProcessor(registry),
                new CommandNodeProcessor(registry),
                new ToolNodeProcessor(registry),
                // EXPORT 单独一个：它的返回值是 HTTP 响应体，不能用通用处理器的返回类型约束
                new ExportNodeProcessor(registry)
        );
    }

    @Override
    public void process(Object instance, BeanDefinition definition) {
        for (NodeProcessor processor : processors) {
            processor.process(instance, definition);
        }
    }
}
