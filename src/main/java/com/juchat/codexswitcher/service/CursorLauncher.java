package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.LaunchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CursorLauncher {
    private final AppPaths paths;
    private final ProcessManager processManager;

    public CursorLauncher(AppPaths paths, ProcessManager processManager) {
        this.paths = paths;
        this.processManager = processManager;
    }

    public LaunchResult launch(int slot, Path accountHome) throws IOException {
        processManager.stopCursorAndCodex();
        Path cursor = findCursorPath();
        ProcessBuilder builder = new ProcessBuilder(cursor.toString());
        builder.directory(paths.userHome().toFile());
        Map<String, String> environment = builder.environment();
        environment.put("CODEX_HOME", accountHome.toString());
        environment.put("CODEX_ACCOUNT_SLOT", String.valueOf(slot));
        builder.start();
        return new LaunchResult(slot, accountHome, cursor);
    }

    public Path findCursorPath() throws IOException {
        String override = System.getProperty("codex.switcher.cursorExe");
        if (override != null && !override.isBlank()) {
            Path overridePath = Paths.get(override);
            if (Files.isRegularFile(overridePath)) {
                return overridePath.toAbsolutePath().normalize();
            }
        }

        for (Path candidate : cursorCandidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Cursor.exe was not found.");
    }

    private List<Path> cursorCandidates() {
        List<Path> candidates = new ArrayList<>();
        String localAppData = getenvIgnoreCase("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(Paths.get(localAppData, "Programs", "Cursor", "Cursor.exe"));
        }
        String programFiles = getenvIgnoreCase("ProgramFiles");
        if (programFiles != null) {
            candidates.add(Paths.get(programFiles, "Cursor", "Cursor.exe"));
        }
        String programFilesX86 = getenvIgnoreCase("ProgramFiles(x86)");
        if (programFilesX86 != null) {
            candidates.add(Paths.get(programFilesX86, "Cursor", "Cursor.exe"));
        }

        String path = getenvIgnoreCase("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    candidates.add(Paths.get(entry, "cursor.exe"));
                    candidates.add(Paths.get(entry, "Cursor.exe"));
                }
            }
        }
        return candidates;
    }

    private static String getenvIgnoreCase(String key) {
        String value = System.getenv(key);
        if (value != null) {
            return value;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
