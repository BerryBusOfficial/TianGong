package com.taixu.tiangong.core.function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultLimiterTest {

    @Test
    void shortResultIsUntouched() {
        assertEquals("ok", ToolResultLimiter.limit("ok", 10));
    }

    @Test
    void longResultIsTruncatedWithSummary() {
        String limited = ToolResultLimiter.limit("abcdefghij", 4);
        assertTrue(limited.startsWith("abcd"));
        assertTrue(limited.contains("omitted 6 chars"));
        assertTrue(limited.contains("total 10"));
    }
}
