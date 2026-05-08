package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.AuthSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTokenParserTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesEmailAndExpirationFromIdToken() throws Exception {
        Path auth = tempDir.resolve("auth.json");
        Files.writeString(auth, TestTokens.authJson("user@example.com", 1893456000L), StandardCharsets.UTF_8);

        AuthSummary summary = new AuthTokenParser().parse(auth);

        assertTrue(summary.isPresent());
        assertEquals("user@example.com", summary.getEmail());
        assertTrue(summary.getExpires().startsWith("2030-01-01"));
    }

    @Test
    void missingFileReturnsMissingSummary() {
        AuthSummary summary = new AuthTokenParser().parse(tempDir.resolve("missing.json"));

        assertFalse(summary.isPresent());
        assertEquals("", summary.getEmail());
    }
}
