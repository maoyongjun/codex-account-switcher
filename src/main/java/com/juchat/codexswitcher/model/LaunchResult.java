package com.juchat.codexswitcher.model;

import java.nio.file.Path;

public final class LaunchResult {
    private final int slot;
    private final Path accountHome;
    private final Path executablePath;
    private final String targetName;

    public LaunchResult(int slot, Path accountHome, Path executablePath) {
        this(slot, accountHome, executablePath, "Cursor");
    }

    public LaunchResult(int slot, Path accountHome, Path executablePath, String targetName) {
        this.slot = slot;
        this.accountHome = accountHome;
        this.executablePath = executablePath;
        this.targetName = targetName;
    }

    public int getSlot() {
        return slot;
    }

    public Path getAccountHome() {
        return accountHome;
    }

    public Path getCursorPath() {
        return executablePath;
    }

    public Path getExecutablePath() {
        return executablePath;
    }

    public String getTargetName() {
        return targetName;
    }
}
