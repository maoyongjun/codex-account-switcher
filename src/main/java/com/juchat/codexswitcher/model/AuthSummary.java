package com.juchat.codexswitcher.model;

public final class AuthSummary {
    private final String email;
    private final String expires;
    private final boolean present;

    public AuthSummary(String email, String expires, boolean present) {
        this.email = email == null ? "" : email;
        this.expires = expires == null ? "" : expires;
        this.present = present;
    }

    public static AuthSummary missing() {
        return new AuthSummary("", "", false);
    }

    public static AuthSummary unreadable() {
        return new AuthSummary("unreadable", "", true);
    }

    public String getEmail() {
        return email;
    }

    public String getExpires() {
        return expires;
    }

    public boolean isPresent() {
        return present;
    }
}
