package com.juchat.codexswitcher.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class TestTokens {
    private TestTokens() {
    }

    static String authJson(String email, long exp) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"email\":\"" + email + "\",\"exp\":" + exp + "}");
        return "{\"tokens\":{\"id_token\":\"" + header + "." + payload + ".sig\"}}";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
