package com.juchat.codexswitcher.service;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {
    private final Path userHome;

    public AppPaths(Path userHome) {
        this.userHome = userHome.toAbsolutePath().normalize();
    }

    public static AppPaths fromSystem() {
        String override = System.getProperty("codex.switcher.userHome");
        String home = override == null || override.isBlank() ? System.getProperty("user.home") : override;
        return new AppPaths(Paths.get(home));
    }

    public Path userHome() {
        return userHome;
    }

    public Path legacyHome() {
        return userHome.resolve(".codex");
    }

    public Path sharedHome() {
        return userHome.resolve(".codex-shared");
    }

    public Path accountHome(int slot) {
        return userHome.resolve(".codex-account" + slot);
    }

    public Path restoreBackupRoot() {
        return userHome.resolve(".codex-switcher-restore-backup");
    }
}
