package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.LaunchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
        return launchCursor(slot, accountHome);
    }

    public LaunchResult launchCursor(int slot, Path accountHome) throws IOException {
        processManager.stopCursorAndCodex();
        Path cursor = findCursorPath();
        ProcessBuilder builder = new ProcessBuilder(cursor.toString());
        startWithAccountEnvironment(builder, slot, accountHome);
        return new LaunchResult(slot, accountHome, cursor, "Cursor");
    }

    public LaunchResult launchCodexApp(int slot, Path accountHome) throws IOException {
        processManager.stopCursorAndCodex();
        Path codex = findCodexPath();
        String executableName = codex.getFileName().toString();
        ProcessBuilder builder = executableName.equalsIgnoreCase("codex.exe") || executableName.equalsIgnoreCase("codex")
                ? new ProcessBuilder(codex.toString(), "app")
                : new ProcessBuilder(codex.toString());
        startWithAccountEnvironment(builder, slot, accountHome);
        return new LaunchResult(slot, accountHome, codex, "Codex");
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

    public Path findCodexPath() throws IOException {
        String override = System.getProperty("codex.switcher.codexExe");
        if (override != null && !override.isBlank()) {
            Path overridePath = Paths.get(override);
            if (Files.isRegularFile(overridePath)) {
                return overridePath.toAbsolutePath().normalize();
            }
        }

        for (Path candidate : codexCandidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("codex executable was not found.");
    }

    private void startWithAccountEnvironment(ProcessBuilder builder, int slot, Path accountHome) throws IOException {
        builder.directory(paths.userHome().toFile());
        Map<String, String> environment = builder.environment();
        environment.put("CODEX_HOME", accountHome.toString());
        environment.put("CODEX_ACCOUNT_SLOT", String.valueOf(slot));
        builder.start();
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

    private List<Path> codexCandidates() {
        List<Path> candidates = new ArrayList<>();
        String localAppData = getenvIgnoreCase("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(Paths.get(localAppData, "Programs", "Codex", "Codex.exe"));
        }

        addPathCandidates(candidates, "codex.exe", "codex", "Codex.exe");

        Path extensions = paths.userHome().resolve(".cursor").resolve("extensions");
        if (Files.isDirectory(extensions)) {
            try (var stream = Files.walk(extensions, 4)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase("codex.exe"))
                        .forEach(candidates::add);
            } catch (IOException ignored) {
                // PATH and explicit locations are still checked.
            }
        }
        return candidates;
    }

    private static void addPathCandidates(List<Path> candidates, String... names) {
        String path = getenvIgnoreCase("PATH");
        if (path == null) {
            return;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                Arrays.stream(names).map(name -> Paths.get(entry, name)).forEach(candidates::add);
            }
        }
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
