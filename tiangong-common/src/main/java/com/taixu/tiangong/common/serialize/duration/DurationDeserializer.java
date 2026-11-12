package com.taixu.tiangong.common.serialize.duration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DurationDeserializer extends StdDeserializer<Duration> {

    private static final Pattern PATTERN = Pattern.compile("(\\d+)([dhms])");

    public DurationDeserializer() {
        super(Duration.class);
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String str = p.getValueAsString();
        if (str == null || str.isBlank()) {
            return null;
        }

        // 移除所有空格，便于解析 "5d 5s" -> "5d5s"
        String clean = str.replaceAll("\\s+", "");
        Matcher matcher = PATTERN.matcher(clean);

        long totalSeconds = 0;
        while (matcher.find()) {
            long num = Long.parseLong(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            totalSeconds += switch (unit) {
                case 'd' -> num * 86400; // 24 * 60 * 60
                case 'h' -> num * 3600;
                case 'm' -> num * 60;
                case 's' -> num;
                default -> 0;
            };
        }
        return Duration.ofSeconds(totalSeconds);
    }
}