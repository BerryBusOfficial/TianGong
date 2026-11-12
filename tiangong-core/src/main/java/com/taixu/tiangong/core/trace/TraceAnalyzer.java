package com.taixu.tiangong.core.trace;

import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字节码静态分析：扫描类中各方法体写入（PUTFIELD）的字段。<br>
 * 用于 @Trace 字段级跟踪——只拦截"会修改被跟踪字段"的方法。分析结果按类缓存。
 */
@Slf4j
public final class TraceAnalyzer {

    /**
     * 类 → (方法签名 → 该方法写入的字段名集合)
     */
    private static final Map<Class<?>, Map<MethodSignature, Set<String>>> CACHE = new ConcurrentHashMap<>();

    private TraceAnalyzer() {
    }

    /**
     * 获取类中每个方法写入的字段名集合（PUTFIELD 指令静态分析，缓存）。
     * 分析失败（字节码不可读等）返回空表。
     */
    public static Map<MethodSignature, Set<String>> writtenFieldsByMethod(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, TraceAnalyzer::analyze);
    }

    private static Map<MethodSignature, Set<String>> analyze(Class<?> clazz) {
        Map<MethodSignature, Set<String>> result = new HashMap<>();
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("TraceAnalyzer 无法读取字节码: {}", clazz.getName());
                return result;
            }
            ClassReader reader = new ClassReader(in);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    Set<String> writtenFields = new HashSet<>();
                    result.put(new MethodSignature(name, desc), writtenFields);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                            if (opcode == Opcodes.PUTFIELD) {
                                writtenFields.add(fieldName);
                            }
                        }
                    };
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    return null;
                }
            }, 0);
        } catch (Exception e) {
            log.warn("TraceAnalyzer 分析失败: {} - {}", clazz.getName(), e.getMessage());
        }
        return result;
    }

    /**
     * 方法签名：名称 + 描述符
     */
    public record MethodSignature(String name, String desc) {
    }
}
