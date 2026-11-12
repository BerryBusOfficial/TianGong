package com.taixu.tiangong.common.serialize.arguments;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.taixu.tiangong.common.function.Arguments;

import java.io.IOException;

public class ArgumentsSerializer extends StdSerializer<Arguments> {

    protected ArgumentsSerializer() {
        super(Arguments.class);
    }

    @Override
    public void serialize(Arguments value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        for (Arguments.Parameter<?> param : value.getParameters()) {
            gen.writeObjectField(param.paramName(), param.value());
        }
        gen.writeEndObject();
    }
}
