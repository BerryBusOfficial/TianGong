package org.wutong.tiangong.annotations.processor;

import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.annotations.annotation.framework.agent.ToolNames;
import org.wutong.tiangong.annotations.processor.util.NameUtils;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code @ToolNames} 注解处理器：为一个工具类生成「工具名常量类」。<br>
 * <pre>{@code
 * @AgentTool
 * @ToolNames
 * public class SystemTool {
 *     @AgentFunction(name = "get_sys_time", description = "获取系统当前时间")
 *     public String getSystemTime() { ... }
 *
 *     @AgentFunction(name = "sys_notice", description = "弹出系统通知")
 *     public boolean notice(...) { ... }
 * }
 * }</pre>
 * 生成 {@code org.wutong.tiangong.user.tool.SystemToolNames}：
 * <pre>{@code
 * public final class SystemToolNames {
 *     public static final String GET_SYSTEM_TIME = "get_sys_time";
 *     public static final String NOTICE = "sys_notice";
 * }
 * }</pre>
 *
 * <h2>取值规则必须和运行期对齐</h2>
 * {@code AgentFunctionProcessor} 注册工具名时用的是
 * {@code name().isBlank() ? method.getName() : name()}；这里必须照抄同一条规则，
 * 否则「生成的常量」与「注册表里的名字」会在 {@code name} 留空时错位 ——
 * 而且错位不会报错，只会表现为按常量去查工具时查不到。
 *
 * <h2>为什么用 TYPE 上的注解而不是直接扫 {@code @AgentFunction}</h2>
 * 显式标注才能决定<b>生成什么类名</b>；若隐式给每个带 {@code @AgentFunction} 的类都生成，
 * 那些压根不想暴露工具类（比如内部的壳封装）会被动多出一堆垃圾类。
 */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ToolNamesProcessor extends AbstractProcessor {

    /**
     * 同 {@link StorageRecordProcessor}：用 class 字面量而不是字符串写全限定名。<br>
     * 字符串写错了不会报错，只会让处理器安静地不跑 —— 排错成本很高。
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ToolNames.class.getName());
    }

    private static final String SUFFIX = "Names";

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv
    ) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ToolNames.class)) {
            if (element instanceof TypeElement type) {
                generate(type);
            }
        }
        return true;
    }

    private void generate(TypeElement type) {
        ToolNames ann = type.getAnnotation(ToolNames.class);
        if (ann == null) {
            return;
        }

        Map<String, String> constants = collectConstants(type);   // 常量名 → 工具名
        if (constants.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@ToolNames 标在一个没有 @AgentFunction 方法的类上，不生成常量类（没人会用到它）",
                    type);
            return;
        }

        String generatedName = resolveGeneratedName(type, ann);
        writeSource(type, generatedName, constants);
    }

    /**
     * 收集方法上的工具名。<br>
     * 只看<b>本类声明</b>的方法 —— 运行期 {@code AgentFunctionProcessor} 用的也是
     * {@code getDeclaredMethods()}，继承来的方法两边都不认。
     */
    private Map<String, String> collectConstants(TypeElement type) {
        Map<String, String> constants = new LinkedHashMap<>();   // 保序：生成顺序跟着源码走
        Map<String, String> owners = new LinkedHashMap<>();      // 常量名 → 方法名，冲突时点名

        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            AgentFunction fn = method.getAnnotation(AgentFunction.class);
            if (fn == null) {
                continue;
            }

            String methodName = method.getSimpleName().toString();
            // 取值规则与运行期 AgentFunctionProcessor 保持一致（详见类注释）
            String functionName = fn.name().isBlank() ? methodName : fn.name();
            String constantName = NameUtils.toUpperSnake(methodName);

            String previous = constants.put(constantName, functionName);
            if (previous != null && !previous.equals(functionName)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "工具常量名冲突：方法 [" + owners.get(constantName) + "] 与 [" + methodName
                                + "] 都会生成 " + constantName + "（工具名分别为 " + previous
                                + " 与 " + functionName + "）。请改名，或用 @ToolNames(\"...\") 换一个类名区分。",
                        type);
                return Map.of();
            }
            owners.put(constantName, methodName);
        }
        return constants;
    }

    private void writeSource(
            TypeElement type,
            String generatedName,
            Map<String, String> constants
    ) {
        String packageName = processingEnv.getElementUtils()
                .getPackageOf(type).getQualifiedName().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/**\n");
        sb.append(" * 由 @ToolNames 注解处理器自动生成，别手改 —— 要改去改 ").append(type.getSimpleName())
                .append(" 里的 @AgentFunction。\n");
        sb.append(" * <p>每个常量的值都取自源文件的 {@code @AgentFunction(name = ...)}，\n");
        sb.append(" * 与运行期注册进 FunctionRegistry 的名字同源。\n");
        sb.append(" */\n");
        sb.append("public final class ").append(generatedName).append(" {\n\n");
        sb.append("    private ").append(generatedName).append("() {\n");
        sb.append("        // 常量类，不需要实例\n");
        sb.append("    }\n\n");

        for (Map.Entry<String, String> e : constants.entrySet()) {
            sb.append("    public static final String ").append(e.getKey())
                    .append(" = \"").append(escape(e.getValue())).append("\";\n\n");
        }

        sb.append("}\n");

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    packageName + "." + generatedName, type);
            try (Writer writer = file.openWriter()) {
                writer.write(sb.toString());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "生成 " + generatedName + " 失败: " + e.getMessage(),
                    type
            );
        }
    }

    /**
     * 生成的类名：{@code @ToolNames("Xxx")} 指定优先，否则 {@code <类名>Names}。
     */
    private String resolveGeneratedName(TypeElement type, ToolNames ann) {
        String custom = ann.value() == null ? "" : ann.value().trim();
        if (!custom.isEmpty()) {
            if (!SourceVersion.isIdentifier(custom) || SourceVersion.isKeyword(custom)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@ToolNames(\"" + custom + "\") 不是一个合法的 Java 类名",
                        type);
            }
            return custom;
        }
        return type.getSimpleName() + SUFFIX;
    }

    private String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
