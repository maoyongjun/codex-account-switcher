package com.juchat.codexswitcher.model;

import java.nio.file.Path;

public final class RestoreResult {
    private final Path backupRoot;

    public RestoreResult(Path backupRoot) {
        this.backupRoot = backupRoot;
    }

    public Path getBackupRoot() {
        return backupRoot;
    }
}
