package org.wutong.tiangong.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.entity.SomeTypeReference;
import org.wutong.tiangong.common.handler.UnknownPropertyWarningHandler;
import org.wutong.tiangong.common.resource.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Map;

@Slf4j
public class YamlUtil {

    @Getter
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .addHandler(new UnknownPropertyWarningHandler());

    public static Map<String, Object> loadYaml(String path) throws IOException {
        try (InputStream stream = ResourceLoader.getResourceAsStream(path)) {
            if (stream == null) {
                log.debug("配置文件不存在，跳过加载");
                return Map.of();
            }
            PushbackInputStream pushback = new PushbackInputStream(stream);
            int firstByte = pushback.read();
            if (firstByte == -1) {
                log.debug("空配置文件，跳过加载");
                return Map.of();
            }
            pushback.unread(firstByte);
            return YAML_MAPPER.readValue(pushback, SomeTypeReference.MAP_STRING_OBJECT);
        }
    }

}
