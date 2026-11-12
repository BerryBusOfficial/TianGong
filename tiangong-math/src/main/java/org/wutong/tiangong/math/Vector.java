package org.wutong.tiangong.math;

import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.math.function.Product;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Vector {

    private final double[] values;

    public Vector(double[] values) {
        this.values = values;
    }

    public Vector(List<Double> values) {
        this.values = values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public double[] values() {
        return values;
    }

    public Double[] genericValues() {
        return Arrays.stream(values.clone()).boxed().toArray(Double[]::new);
    }

    public int length() {
        return values.length;
    }

    public double magnitude() {
        return Math.sqrt(
                Arrays.stream(values)
                        .map(d -> Math.pow(d, 2))
                        .sum()
        );
    }

    public List<Double> toList() {
        return Arrays.stream(values)
                .boxed()
                .collect(Collectors.toList());
    }

    public Vector reverse() {
        double[] newDoubleValues = values.clone();
        return new Vector(
                Arrays.stream(newDoubleValues)
                        .boxed()
                        .collect(Collectors.toList())
                        .reversed()
        );
    }

    private void checkLength(Vector vector) {
        if (this.length() != vector.length()) {
            throw new SystemRuntimeException(
                    "向量维度不统一：%s <=> %s".formatted(this.length(), vector.length()),
                    new IllegalArgumentException()
            );
        }
    }

    public Vector add(Vector vector) {
        checkLength(vector);
        List<Double> doubles = new ArrayList<>(this.length());
        for (int i = 0; i < this.length(); i++) {
            doubles.add(this.values()[i] + vector.values()[i]);
        }
        return new Vector(doubles);
    }

    public Vector subtract(Vector vector) {
        checkLength(vector);
        List<Double> doubles = new ArrayList<>(this.length());
        for (int i = 0; i < this.length(); i++) {
            doubles.add(this.values()[i] - vector.values()[i]);
        }
        return new Vector(doubles);
    }

    public Vector multiply(Vector vector) {
        checkLength(vector);
        List<Double> doubles = new ArrayList<>(this.length());
        for (int i = 0; i < this.length(); i++) {
            doubles.add(this.values()[i] * vector.values()[i]);
        }
        return new Vector(doubles);
    }

    public double dot(Vector vector) {
        checkLength(vector);
        double result = 0;
        for (int i = 0; i < this.length(); i++) {
            result += this.values()[i] * vector.values()[i];
        }
        return result;
    }

    public double cos(Vector vector) {
        checkLength(vector);
        return dot(vector) / new Product<Double>().apply(
                new Product.ProductFunctionParam<>(this.magnitude(), vector.magnitude())
        );
    }

}
