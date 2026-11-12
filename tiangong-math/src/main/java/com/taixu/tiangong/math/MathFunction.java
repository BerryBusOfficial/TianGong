package com.taixu.tiangong.math;

public interface MathFunction<P extends Param, T extends Number> {

    T apply(P p);

}
