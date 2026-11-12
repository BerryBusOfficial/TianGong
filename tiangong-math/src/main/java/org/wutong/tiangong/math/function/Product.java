package org.wutong.tiangong.math.function;

import com.fasterxml.jackson.core.type.TypeReference;
import org.wutong.tiangong.math.MathFunction;
import org.wutong.tiangong.math.Param;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Product<T extends Number> implements MathFunction<Product.ProductFunctionParam<T>, T> {

    @Override
    @SuppressWarnings("unchecked")
    public T apply(ProductFunctionParam<T> param) {
        List<Double> params = param.getParam("params", new TypeReference<>() {
        });

        // 处理空列表的情况（空乘积为 1）
        if (params == null || params.isEmpty()) {
            return (T) Integer.valueOf(1);
        }

        BigDecimal decimal = BigDecimal.ONE;
        for (Double value : params) {
            decimal = decimal.multiply(BigDecimal.valueOf(value));
        }

        // 根据第一个参数的实际类型，将 BigDecimal 转换为对应的 T
        return (T) Double.valueOf(decimal.doubleValue());
    }

    public static final class ProductFunctionParam<T extends Number> extends Param {

        @SafeVarargs
        public ProductFunctionParam(T... params) {
            super(Map.of("params", Arrays.stream(params)
                    .map(Number::doubleValue)
                    .collect(Collectors.toList())));
        }
    }

}
