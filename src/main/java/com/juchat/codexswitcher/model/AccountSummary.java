package com.juchat.codexswitcher.model;

import java.nio.file.Path;

public final class AccountSummary {
    private final int slot;
    private final Path home;
    private final String email;
    private final String expires;
    private final boolean authPresent;
    private final boolean prepared;

    public AccountSummary(int slot, Path home, String email, String expires, boolean authPresent, boolean prepared) {
        this.slot = slot;
        this.home = home;
        this.email = email == null ? "" : email;
        this.expires = expires == null ? "" : expires;
        this.authPresent = authPresent;
        this.prepared = prepared;
    }

    public int getSlot() {
        return slot;
    }

    public Path getHome() {
        return home;
    }

    public String getEmail() {
        return email;
    }

    public String getExpires() {
        return expires;
    }

    public boolean isAuthPresent() {
        return authPresent;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public String displayEmail() {
        if (!authPresent) {
            return "not logged in";
        }
        return email.isBlank() ? "auth present" : email;
    }
}
