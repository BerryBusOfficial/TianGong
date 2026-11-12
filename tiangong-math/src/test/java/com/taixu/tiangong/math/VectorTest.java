package com.taixu.tiangong.math;

import org.junit.jupiter.api.Test;

class VectorTest {

    @Test
    void cos() {
        double[] A = new double[]{1.0, 2.0, 3.0};
        double[] B = new double[]{2.0, 3.0, 4.0};
        System.out.println(new Vector(A).cos(new Vector(B)));
    }
}