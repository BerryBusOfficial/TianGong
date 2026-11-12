package org.wutong.tiangong.common.serialize.arguments;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.wutong.tiangong.common.function.Arguments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ArgumentsDeserializer extends StdDeserializer<Arguments> {

    protected ArgumentsDeserializer() {
        super(Arguments.class);
    }

    @Override
    public Arguments deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (!node.isObject()) {
            throw JsonMappingException.from(p, "Expected JSON Object");
        }

        List<Arguments.Parameter<?>> parameters = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String name = entry.getKey();
            JsonNode valueNode = entry.getValue();
            Object value = p.getCodec().treeToValue(valueNode, Object.class);
            parameters.add(new Arguments.Parameter<>(name, value));
        }
        return new Arguments(parameters);
    }
}
