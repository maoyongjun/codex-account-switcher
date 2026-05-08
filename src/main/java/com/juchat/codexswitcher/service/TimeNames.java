package com.juchat.codexswitcher.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class TimeNames {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TimeNames() {
    }

    static String timestamp() {
        return FILE_TIMESTAMP.format(LocalDateTime.now());
    }
}
