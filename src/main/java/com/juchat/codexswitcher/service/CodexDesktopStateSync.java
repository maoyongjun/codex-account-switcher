package com.juchat.codexswitcher.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodexDesktopStateSync {
    private static final int RECENT_THREAD_LIMIT = 50;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Pattern SESSION_TIMESTAMP_PATTERN = Pattern.compile("\"timestamp\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SESSION_CWD_PATTERN = Pattern.compile("\"cwd\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern JSON_STRING_ENTRY_PATTERN =
            Pattern.compile("\"((?:\\\\.|[^\"])*)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private CodexDesktopStateSync() {
    }

    static void syncRecentThreads(Path accountHome, Path desktopHome) throws IOException {
        ensureArchivedSessionsIndexed(accountHome);

        List<ThreadHint> recentThreads = readRecentThreadHints(accountHome, RECENT_THREAD_LIMIT);
        if (recentThreads.isEmpty()) {
            return;
        }

        Files.createDirectories(desktopHome);
        Path statePath = desktopHome.resolve(".codex-global-state.json");
        String state = Files.exists(statePath) ? Files.readString(statePath, StandardCharsets.UTF_8) : "{}";

        Set<String> recentThreadIds = new LinkedHashSet<>();
        for (ThreadHint thread : recentThreads) {
            recentThreadIds.add(thread.id());
        }

        Set<String> projectlessThreadIds = new LinkedHashSet<>(readStringArrayProperty(state, "projectless-thread-ids"));
        projectlessThreadIds.removeAll(recentThreadIds);

        Map<String, String> workspaceHints = new LinkedHashMap<>();
        for (ThreadHint thread : recentThreads) {
            workspaceHints.put(thread.id(), thread.cwd());
        }
        readStringObjectProperty(state, "thread-workspace-root-hints")
                .forEach(workspaceHints::putIfAbsent);

        state = upsertStringArrayProperty(state, "projectless-thread-ids", projectlessThreadIds);
        state = upsertStringObjectProperty(state, "thread-workspace-root-hints", workspaceHints);
        Files.writeString(statePath, state, StandardCharsets.UTF_8);
        Files.writeString(desktopHome.resolve(".codex-global-state.json.bak"), state, StandardCharsets.UTF_8);
    }

    private static void ensureArchivedSessionsIndexed(Path accountHome) throws IOException {
        Path index = accountHome.resolve("session_index.jsonl");
        Path archivedSessions = accountHome.resolve("archived_sessions");
        if (!Files.isDirectory(archivedSessions)) {
            return;
        }

        List<String> lines = Files.exists(index)
                ? Files.readAllLines(index, StandardCharsets.UTF_8)
                : new ArrayList<>();
        Set<String> indexedIds = new LinkedHashSet<>();
        for (String line : lines) {
            String sessionId = extract(SESSION_ID_PATTERN, line);
            if (sessionId != null) {
                indexedIds.add(sessionId);
            }
        }

        List<IndexedThread> missingThreads = new ArrayList<>();
        try (var stream = Files.walk(archivedSessions, 5)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                IndexedThread thread = readIndexedThread(file);
                if (thread != null && indexedIds.add(thread.id())) {
                    missingThreads.add(thread);
                }
            }
        }

        if (missingThreads.isEmpty()) {
            return;
        }

        missingThreads.sort((left, right) -> left.updatedAt().compareTo(right.updatedAt()));
        for (IndexedThread thread : missingThreads) {
            lines.add(renderIndexLine(thread));
        }
        Files.createDirectories(index.getParent());
        Files.write(index, lines, StandardCharsets.UTF_8);
    }

    private static List<ThreadHint> readRecentThreadHints(Path accountHome, int limit) throws IOException {
        Path index = accountHome.resolve("session_index.jsonl");
        Path sessions = accountHome.resolve("sessions");
        if (!Files.exists(index) || !Files.isDirectory(sessions)) {
            return List.of();
        }

        List<Path> sessionFiles;
        try (var stream = Files.walk(sessions, 5)) {
            sessionFiles = stream.filter(Files::isRegularFile).toList();
        }

        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        List<ThreadHint> hints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = lines.size() - 1; i >= 0 && hints.size() < limit; i--) {
            String sessionId = extract(SESSION_ID_PATTERN, lines.get(i));
            if (sessionId == null || !seen.add(sessionId)) {
                continue;
            }

            Path sessionFile = findSessionFile(sessionFiles, sessionId);
            if (sessionFile == null) {
                continue;
            }

            Path cwd = readSessionCwd(sessionFile);
            if (cwd != null && Files.isDirectory(cwd)) {
                hints.add(new ThreadHint(sessionId, cwd.toAbsolutePath().normalize().toString()));
            }
        }
        return hints;
    }

    private static Path findSessionFile(List<Path> sessionFiles, String sessionId) {
        return sessionFiles.stream()
                .filter(path -> path.getFileName().toString().contains(sessionId))
                .findFirst()
                .orElse(null);
    }

    private static Path readSessionCwd(Path sessionFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            String cwd = firstLine == null ? null : extract(SESSION_CWD_PATTERN, firstLine);
            return cwd == null || cwd.isBlank() ? null : Paths.get(unescapeJson(cwd));
        }
    }

    private static IndexedThread readIndexedThread(Path sessionFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            String sessionId = extract(SESSION_ID_PATTERN, firstLine);
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = extract(UUID_PATTERN, sessionFile.getFileName().toString());
            }
            if (sessionId == null || sessionId.isBlank()) {
                return null;
            }

            String updatedAt = extract(SESSION_TIMESTAMP_PATTERN, firstLine);
            if (updatedAt == null || updatedAt.isBlank()) {
                updatedAt = Files.getLastModifiedTime(sessionFile).toInstant().toString();
            }
            return new IndexedThread(sessionId, "Archived " + updatedAt, updatedAt);
        }
    }

    private static List<String> readStringArrayProperty(String json, String key) {
        Matcher property = arrayPropertyPattern(key).matcher(json);
        if (!property.find()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        Matcher strings = JSON_STRING_PATTERN.matcher(property.group(1));
        while (strings.find()) {
            values.add(unescapeJson(strings.group(1)));
        }
        return values;
    }

    private static Map<String, String> readStringObjectProperty(String json, String key) {
        Matcher property = objectPropertyPattern(key).matcher(json);
        if (!property.find()) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        Matcher entries = JSON_STRING_ENTRY_PATTERN.matcher(property.group(1));
        while (entries.find()) {
            values.put(unescapeJson(entries.group(1)), unescapeJson(entries.group(2)));
        }
        return values;
    }

    private static String upsertStringArrayProperty(String json, String key, Collection<String> values) {
        return upsertProperty(json, arrayPropertyPattern(key), quoteJson(key) + ":" + renderStringArray(values));
    }

    private static String upsertStringObjectProperty(String json, String key, Map<String, String> values) {
        return upsertProperty(json, objectPropertyPattern(key), quoteJson(key) + ":" + renderStringObject(values));
    }

    private static String upsertProperty(String json, Pattern propertyPattern, String property) {
        String normalized = normalizeJsonObject(json);
        Matcher matcher = propertyPattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(property));
        }

        int insertAt = normalized.lastIndexOf('}');
        String beforeClose = normalized.substring(0, insertAt).trim();
        String separator = beforeClose.length() <= 1 ? "" : ",";
        return normalized.substring(0, insertAt) + separator + property + normalized.substring(insertAt);
    }

    private static String normalizeJsonObject(String json) {
        String normalized = json == null || json.isBlank() ? "{}" : json.trim();
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            return "{}";
        }
        return normalized;
    }

    private static String renderStringArray(Collection<String> values) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(',');
            }
            builder.append(quoteJson(value));
            first = false;
        }
        return builder.append(']').toString();
    }

    private static String renderStringObject(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(quoteJson(entry.getKey())).append(':').append(quoteJson(entry.getValue()));
            first = false;
        }
        return builder.append('}').toString();
    }

    private static String renderIndexLine(IndexedThread thread) {
        return "{" + quoteJson("id") + ":" + quoteJson(thread.id())
                + "," + quoteJson("thread_name") + ":" + quoteJson(thread.threadName())
                + "," + quoteJson("updated_at") + ":" + quoteJson(thread.updatedAt()) + "}";
    }

    private static Pattern arrayPropertyPattern(String key) {
        return Pattern.compile(quoteJson(key) + "\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    }

    private static Pattern objectPropertyPattern(String key) {
        return Pattern.compile(quoteJson(key) + "\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    }

    private static String extract(Pattern pattern, String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String quoteJson(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.append('"').toString();
    }

    private static String unescapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }

            char escaped = value.charAt(++i);
            switch (escaped) {
                case '\\' -> builder.append('\\');
                case '"' -> builder.append('"');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            builder.append("\\u").append(hex);
                            i += 4;
                        }
                    } else {
                        builder.append("\\u");
                    }
                }
                default -> builder.append(escaped);
            }
        }
        return builder.toString();
    }

    private record ThreadHint(String id, String cwd) {
    }

    private record IndexedThread(String id, String threadName, String updatedAt) {
    }
}
