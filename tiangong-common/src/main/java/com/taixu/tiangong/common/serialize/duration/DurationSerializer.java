package com.taixu.tiangong.common.serialize.duration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Duration;

public class DurationSerializer extends JsonSerializer<Duration> {
    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        long days = value.toDaysPart();
        int hours = value.toHoursPart();
        int minutes = value.toMinutesPart();
        int seconds = value.toSecondsPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d');
        if (hours > 0) sb.append(hours).append('h');
        if (minutes > 0) sb.append(minutes).append('m');
        if (seconds > 0) sb.append(seconds).append('s');

        // 如果所有部分都为 0（例如 Duration.ZERO），输出 "0s"
        if (sb.isEmpty()) sb.append("0");

        gen.writeString(sb.toString());
    }
}