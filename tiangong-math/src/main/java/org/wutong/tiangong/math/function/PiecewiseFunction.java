package org.wutong.tiangong.math.function;

import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.math.MathFunction;
import org.wutong.tiangong.math.Param;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PiecewiseFunction<T extends Number> implements MathFunction<PiecewiseFunction.PiecewiseFunctionParam, T> {

    private final Map<Function<T, Boolean>, T> functions = new HashMap<>();

    public PiecewiseFunction(Map<Function<T, Boolean>, T> functions) {
        this.functions.putAll(functions);
    }

    public PiecewiseFunction() {

    }

    public void addCondition(Function<T, Boolean> function, T value) {
        functions.put(function, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T apply(PiecewiseFunctionParam param) {
        T value = null;
        Number raw = (Number) param.getParam("value");
        for (Map.Entry<Function<T, Boolean>, T> entry : functions.entrySet()) {
            if (entry.getKey().apply(toTarget(raw, entry.getValue()))) {
                if (value == null) {
                    value = entry.getValue();
                } else {
                    throw new SystemRuntimeException("有多个判断结果为True，定义域重复", new IllegalArgumentException());
                }
            }
        }
        if (value == null) {
            throw new SystemRuntimeException("结果不存在于分段函数中的任何一个条件", new IllegalStateException());
        }
        return value;
    }

    /**
     * 按目标数值类型转换（Integer → Double 等），避免强转失败
     */
    @SuppressWarnings("unchecked")
    private T toTarget(Number source, T targetSample) {
        if (targetSample instanceof Double) {
            return (T) Double.valueOf(source.doubleValue());
        }
        if (targetSample instanceof Float) {
            return (T) Float.valueOf(source.floatValue());
        }
        if (targetSample instanceof Long) {
            return (T) Long.valueOf(source.longValue());
        }
        if (targetSample instanceof Integer) {
            return (T) Integer.valueOf(source.intValue());
        }
        if (targetSample instanceof Short) {
            return (T) Short.valueOf(source.shortValue());
        }
        if (targetSample instanceof Byte) {
            return (T) Byte.valueOf(source.byteValue());
        }
        return (T) source;
    }

    public static final class PiecewiseFunctionParam extends Param {
        public PiecewiseFunctionParam(int value) {
            super(Map.of("value", value));
        }
    }
}
