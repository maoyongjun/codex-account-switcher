package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.LaunchResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CursorLauncher {
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SESSION_CWD_PATTERN = Pattern.compile("\"cwd\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

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
        Path workspace = resolveCodexWorkspace(accountHome);
        String executableName = codex.getFileName().toString();
        ProcessBuilder builder = executableName.equalsIgnoreCase("codex.exe") || executableName.equalsIgnoreCase("codex")
                ? new ProcessBuilder(codex.toString(), "app", workspace.toString())
                : new ProcessBuilder(codex.toString(), workspace.toString());
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

    Path resolveCodexWorkspace(Path accountHome) {
        String override = System.getProperty("codex.switcher.codexWorkspace");
        if (override != null && !override.isBlank()) {
            Path overridePath = Paths.get(override);
            if (Files.isDirectory(overridePath)) {
                return overridePath.toAbsolutePath().normalize();
            }
        }

        try {
            Path recentWorkspace = findRecentSessionWorkspace(accountHome);
            if (recentWorkspace != null) {
                return recentWorkspace;
            }
        } catch (IOException ignored) {
            // Falling back to user home still lets Codex launch; the UI may show only projectless threads.
        }
        return paths.userHome();
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

    private Path findRecentSessionWorkspace(Path accountHome) throws IOException {
        Path index = accountHome.resolve("session_index.jsonl");
        Path sessions = accountHome.resolve("sessions");
        if (!Files.exists(index) || !Files.isDirectory(sessions)) {
            return null;
        }

        List<String> lines = Files.readAllLines(index);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String sessionId = extract(SESSION_ID_PATTERN, lines.get(i));
            if (sessionId == null) {
                continue;
            }
            Path sessionFile = findSessionFile(sessions, sessionId);
            if (sessionFile == null) {
                continue;
            }
            Path cwd = readSessionCwd(sessionFile);
            if (cwd != null && Files.isDirectory(cwd)) {
                return cwd.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path findSessionFile(Path sessions, String sessionId) throws IOException {
        try (var stream = Files.walk(sessions, 5)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(sessionId))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path readSessionCwd(Path sessionFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String firstLine = reader.readLine();
            String cwd = firstLine == null ? null : extract(SESSION_CWD_PATTERN, firstLine);
            return cwd == null || cwd.isBlank() ? null : Paths.get(unescapeJson(cwd));
        }
    }

    private static String extract(Pattern pattern, String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\\", "\\").replace("\\\"", "\"");
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
