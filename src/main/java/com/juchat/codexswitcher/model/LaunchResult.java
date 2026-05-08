package com.juchat.codexswitcher.model;

import java.nio.file.Path;

public final class LaunchResult {
    private final int slot;
    private final Path accountHome;
    private final Path cursorPath;

    public LaunchResult(int slot, Path accountHome, Path cursorPath) {
        this.slot = slot;
        this.accountHome = accountHome;
        this.cursorPath = cursorPath;
    }

    public int getSlot() {
        return slot;
    }

    public Path getAccountHome() {
        return accountHome;
    }

    public Path getCursorPath() {
        return cursorPath;
    }
}
