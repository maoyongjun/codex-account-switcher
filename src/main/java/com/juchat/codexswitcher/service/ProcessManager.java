package com.juchat.codexswitcher.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class ProcessManager {
    private final long settleMillis;

    public ProcessManager() {
        this(2000L);
    }

    public ProcessManager(long settleMillis) {
        this.settleMillis = settleMillis;
    }

    public void stopCursorAndCodex() {
        ProcessHandle.allProcesses()
                .filter(this::isCursorOrCodex)
                .forEach(process -> {
                    try {
                        process.destroyForcibly();
                    } catch (RuntimeException ignored) {
                        // Best-effort stop; launch can still continue and report startup errors if needed.
                    }
                });
        if (settleMillis > 0) {
            try {
                Thread.sleep(settleMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isCursorOrCodex(ProcessHandle process) {
        String command = process.info().command().orElse("");
        if (command.isBlank()) {
            return false;
        }
        String fileName = fileName(command).toLowerCase(Locale.ROOT);
        return fileName.equals("cursor.exe")
                || fileName.equals("cursor")
                || fileName.equals("codex.exe")
                || fileName.equals("codex");
    }

    private static String fileName(String command) {
        try {
            Path path = Paths.get(command);
            Path fileName = path.getFileName();
            return fileName == null ? command : fileName.toString();
        } catch (RuntimeException ex) {
            int slash = Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/'));
            return slash >= 0 ? command.substring(slash + 1) : command;
        }
    }
}
