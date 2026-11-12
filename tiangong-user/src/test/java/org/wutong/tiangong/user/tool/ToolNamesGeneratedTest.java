package org.wutong.tiangong.user.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code @ToolNames} 在编译期生成的常量类。<br>
 * <br>
 * <b>这里刻意直接引用生成的类名</b>（而不是走反射 + Class.forName）：
 * 一旦注解处理器没跑起来，本模块会在<b>编译期</b>就炸，而不是等到运行时才发现
 * 「工具类注册了但没有名字常量」。对一个纯编译期的特性来说，编译失败才是最准的信号。
 */
class ToolNamesGeneratedTest {

    @Test
    @DisplayName("PetTool：常量值取自 @AgentFunction 的 name")
    void petToolConstants() {
        assertEquals("remember_fact", PetToolNames.REMEMBER_FACT);
        assertEquals("forget_fact", PetToolNames.FORGET_FACT);
        assertEquals("recall_facts", PetToolNames.RECALL_FACTS);
        assertEquals("set_mood", PetToolNames.SET_MOOD);
    }

    @Test
    @DisplayName("SystemTool：常量名是方法名转大写下划线，值仍取 name")
    void systemToolConstants() {
        assertEquals("get_sys_time", SystemToolNames.GET_SYSTEM_TIME);
        assertEquals("sys_notice", SystemToolNames.NOTICE);
        assertEquals("sys_read", SystemToolNames.READ);
    }

    @Test
    @DisplayName("生成的常量是 public static final String —— 能用在注解和 switch 里")
    void constantsAreInlinable() throws Exception {
        assertAll(
                () -> assertModifier(PetToolNames.class, "REMEMBER_FACT"),
                () -> assertModifier(SystemToolNames.class, "GET_SYSTEM_TIME")
        );
        // compile-time constant：这个值会被 javac 内联，所以两个引用必须指向同一个常量池对象
        assertSame("remember_fact", PetToolNames.REMEMBER_FACT);
    }

    @Test
    @DisplayName("生成类是 final 且构造私有 —— 别被实例化")
    void generatedClassShape() throws Exception {
        assertAll(
                () -> assertUtilityClass(PetToolNames.class),
                () -> assertUtilityClass(SystemToolNames.class)
        );
    }

    private void assertModifier(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        int mod = field.getModifiers();
        assertTrue(Modifier.isPublic(mod), fieldName + " 应为 public");
        assertTrue(Modifier.isStatic(mod), fieldName + " 应为 static");
        assertTrue(Modifier.isFinal(mod), fieldName + " 应为 final");
        assertEquals(String.class, field.getType(), fieldName + " 应为 String");
    }

    private void assertUtilityClass(Class<?> clazz) throws Exception {
        assertTrue(Modifier.isFinal(clazz.getModifiers()),
                clazz.getSimpleName() + " 应为 final");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                clazz.getSimpleName() + " 的构造器应为 private");
    }
}
