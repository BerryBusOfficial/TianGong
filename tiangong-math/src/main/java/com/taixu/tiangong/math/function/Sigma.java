package com.taixu.tiangong.math.function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.taixu.tiangong.math.MathFunction;
import com.taixu.tiangong.math.Param;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Sigma<T extends Number> implements MathFunction<Sigma.SigmaFunctionParam<T>, T> {

    private final List<T[]> eachList;
    private final Class<T> clazz;
    @SafeVarargs
    public Sigma(Class<T> clazz, T[]... eachList) {
        this.clazz = clazz;
        this.eachList = Arrays.asList(eachList);
    }

    /**
     * 执行求和
     *
     * @param begin     i
     * @param maxLength n
     * @param function  每个列表的第 i 个元素所需要执行的函数操作，其中T[]包含了每个列表的第i个元素
     * @return 从 i 开始，直到 n 的求和
     */
    @SuppressWarnings("unchecked")
    private T apply(int begin, int maxLength, Function<T[], T> function) {
        double result = 0.0;

        // 修复循环边界：i < maxLength 即可
        for (int i = begin - 1; i < maxLength; i++) {
            // 核心修复：使用反射创建真正的 T[] 数组，彻底避免 ClassCastException
            T[] each = (T[]) Array.newInstance(clazz, eachList.size());

            for (int j = 0; j < eachList.size(); j++) {
                each[j] = eachList.get(j)[i];
            }

            // 修复累加逻辑：使用 += 而不是直接覆盖
            result += function.apply(each).doubleValue();
        }

        // 返回结果
        return (T) Double.valueOf(result);
    }

    @Override
    public T apply(SigmaFunctionParam<T> param) {
        int begin = param.getParam("begin", Integer.class);
        int maxLength = param.getParamOrDefault("maxLength", Integer.class, eachList.size());
        Function<T[], T> function = param.getParam("function", new TypeReference<>() {
        });
        return apply(begin, maxLength, function);
    }

    public static final class SigmaFunctionParam<T extends Number> extends Param {
        public SigmaFunctionParam(int begin, int maxLength, Function<T[], T> function) {
            super(Map.of("begin", begin, "maxLength", maxLength, "function", function));
        }

        public SigmaFunctionParam(int begin, Function<T[], T> function) {
            super(Map.of("begin", begin, "function", function));
        }
    }

    public static final class DoubleSigma extends Sigma<Double> {

        public DoubleSigma(Double[]... eachList) {
            super(Double.class, eachList);
        }

    }

    public static final class IntegerSigma extends Sigma<Integer> {
        public IntegerSigma(Integer[]... eachList) {
            super(Integer.class, eachList);
        }
    }

    public static final class FloatSigma extends Sigma<Float> {
        public FloatSigma(Float[]... eachList) {
            super(Float.class, eachList);
        }
    }

    public static final class LongSigma extends Sigma<Long> {
        public LongSigma(Long[]... eachList) {
            super(Long.class, eachList);
        }
    }

    public static final class ShortSigma extends Sigma<Short> {
        public ShortSigma(Short[]... eachList) {
            super(Short.class, eachList);
        }
    }
}
