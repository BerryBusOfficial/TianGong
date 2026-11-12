package com.taixu.tiangong.math;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.math.function.PiecewiseFunction;

class PiecewiseFunctionTest {

    @Test
    void apply() {
        PiecewiseFunction<Double> piecewiseFunction = new PiecewiseFunction<>();
        piecewiseFunction.addCondition((x) -> x < 5, 1d);
        piecewiseFunction.addCondition((x) -> x >= 5, 0d);
        System.out.println(piecewiseFunction.apply(new PiecewiseFunction.PiecewiseFunctionParam(1)));
    }
}