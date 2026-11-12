package com.taixu.tiangong.math.function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.taixu.tiangong.math.MathFunction;
import com.taixu.tiangong.math.Param;

import java.util.Map;

public class Sigmoid<T extends Number> implements MathFunction<Sigmoid.SigmoidFunctionParam<T>, T> {

    @Override
    @SuppressWarnings("unchecked")
    public T apply(SigmoidFunctionParam<T> param) {
        double result = 1.0 / (1.0 + Math.exp(
                param.getParam(
                        "value", new TypeReference<Number>() {
                        }).doubleValue())
        );
        return (T) Double.valueOf(result);
    }

    public static final class SigmoidFunctionParam<T> extends Param {

        public SigmoidFunctionParam(T value) {
            super(Map.of("value", value));
        }

    }

    public static class DoubleSigmoid extends Sigmoid<Double> {
    }

    public static class FloatSigmoid extends Sigmoid<Float> {
    }
}
