package com.taixu.tiangong.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;

/**
 * 提供 {@link TianGongJsonSchemaValidator}（ServiceLoader 注册）。<br>
 * 与 {@link TianGongMcpJsonMapperSupplier} 同一套路：SDK 2.0 的默认实现走 Jackson 3，
 * 而项目是 Jackson 2，所以 SPI 由我们自己填。
 */
public class TianGongJsonSchemaValidatorSupplier implements JsonSchemaValidatorSupplier {

    @Override
    public TianGongJsonSchemaValidator get() {
        return new TianGongJsonSchemaValidator();
    }
}
