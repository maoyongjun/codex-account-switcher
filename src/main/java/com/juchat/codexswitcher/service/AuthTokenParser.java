package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.AuthSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuthTokenParser {
    private static final Pattern ID_TOKEN_PATTERN = Pattern.compile("\"id_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final DateTimeFormatter EXPIRES_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(ZoneId.systemDefault());

    public AuthSummary parse(Path authPath) {
        if (authPath == null || !Files.exists(authPath)) {
            return AuthSummary.missing();
        }

        try {
            String raw = Files.readString(authPath, StandardCharsets.UTF_8);
            String token = extractIdToken(raw);
            if (token == null || token.isBlank()) {
                return new AuthSummary("", "", true);
            }

            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return new AuthSummary("", "", true);
            }

            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            String email = extractJsonString(payload, "email");
            String expires = extractExpires(payload);
            return new AuthSummary(email, expires, true);
        } catch (IllegalArgumentException | IOException ex) {
            return AuthSummary.unreadable();
        }
    }

    private String extractIdToken(String raw) {
        Matcher matcher = ID_TOKEN_PATTERN.matcher(raw);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : null;
    }

    private static String extractJsonString(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : "";
    }

    private static String extractExpires(String json) {
        Pattern pattern = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        long epochSeconds = Long.parseLong(matcher.group(1));
        return EXPIRES_FORMATTER.format(Instant.ofEpochSecond(epochSeconds));
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }

    private static String unescapeJsonString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
