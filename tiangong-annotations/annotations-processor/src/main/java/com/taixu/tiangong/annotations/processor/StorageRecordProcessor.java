package com.taixu.tiangong.annotations.processor;


import com.taixu.tiangong.annotations.annotation.framework.field.StorageFields;
import com.taixu.tiangong.annotations.annotation.framework.field.StorageRecords;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/**
 * @StorageRecord 注解处理器：为 record 生成 {@code XxxFields} 查询字段类。<br>
 * <pre>
 * {@code
 * @StorageRecord
 * public record HistoryRecord<V extends BaseHistoryItem>(
 *         @StorageField("history_key") String historyKey,
 *         @StorageField V value
 * ) implements StorageRecords<String> {}
 * }
 * </pre>
 * 生成：
 * <pre>
 * public final class HistoryRecordFields {
 *     public static final Field<HistoryRecord<?>, String> HISTORY_KEY =
 *             Field.of("history_key", HistoryRecord::historyKey);
 *     public static final Field<HistoryRecord<?>, BaseHistoryItem> VALUE =
 *             Field.of("value", HistoryRecord::value);
 * }
 * </pre>
 */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class StorageRecordProcessor extends AbstractProcessor {

    /**
     * ⚠ 这里<b>刻意不写字符串全限定名</b>，用 class 字面量 —— 这个处理器曾经把包名写成
     * {@code com.wutong...}（真实是 {@code org.wutong...}），结果就是它一声不吭地
     * <b>从来没运行过</b>：{@code @SupportedAnnotationTypes} 匹配不上时既不报错也不提示，
     * 只表现为「说好的类没生成」。用 class 字面量，包名写错或将来重命名都是<b>编译错误</b>。
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(StorageRecords.class.getName());
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv
    ) {
        for (Element element : roundEnv.getElementsAnnotatedWith(StorageRecords.class)) {
            if (element instanceof TypeElement record) {
                generateFieldsClass(record);
            }
        }
        return true;
    }

    private void generateFieldsClass(TypeElement record) {
        String recordName = record.getSimpleName().toString();
        String fieldsClassName = recordName + "Fields";
        String packageName = processingEnv.getElementUtils()
                .getPackageOf(record).getQualifiedName().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.taixu.tiangong.core.storage.query.Field;\n\n");
        sb.append("/**\n * 由 @StorageRecords 注解处理器自动生成\n */\n");
        sb.append("public final class ").append(fieldsClassName).append(" {\n\n");
        sb.append("    private ").append(fieldsClassName).append("() {\n    }\n\n");

        // record 有类型参数 → R 用通配符（HistoryRecord<?>），否则裸类名
        String recordType = record.getTypeParameters().isEmpty()
                ? recordName
                : recordName + "<?>";

        for (Element enclosed : record.getEnclosedElements()) {
            if (!(enclosed instanceof RecordComponentElement component)) {
                continue;
            }
            StorageFields field = component.getAnnotation(StorageFields.class);
            if (field == null || !field.exist()) {
                continue;
            }

            String componentName = component.getSimpleName().toString();
            String storageKey = resolveStorageKey(field, componentName);
            String constantName = toConstantName(componentName);

            // 值类型：擦除（泛型参数 V → 上界 BaseHistoryItem）
            TypeMirror erasedType = processingEnv.getTypeUtils().erasure(component.asType());
            String valueType = erasedType.toString();

            sb.append("    public static final Field<").append(recordType).append(", ").append(valueType)
                    .append("> ").append(constantName).append(" =\n")
                    .append("            Field.of(\"").append(storageKey).append("\", ")
                    .append(recordName).append("::").append(componentName).append(");\n\n");
        }

        sb.append("}\n");

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    packageName + "." + fieldsClassName, record);
            try (Writer writer = file.openWriter()) {
                writer.write(sb.toString());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "生成 " + fieldsClassName + " 失败: " + e.getMessage(),
                    record
            );
        }
    }

    /**
     * 存储键：@StorageFields.value → name → 组件名
     */
    private String resolveStorageKey(StorageFields field, String componentName) {
        if (field != null && !field.value().isBlank()) {
            return field.value();
        }
        if (field != null && !field.name().isBlank()) {
            return field.name();
        }
        return componentName;
    }

    /**
     * camelCase → UPPER_SNAKE（historyKey → HISTORY_KEY）
     */
    private String toConstantName(String componentName) {
        StringBuilder sb = new StringBuilder();
        for (char c : componentName.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}
