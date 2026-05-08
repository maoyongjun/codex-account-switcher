package com.juchat.codexswitcher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class LinkService {
    public static final List<String> SHARED_DIRS = List.of("sessions", "archived_sessions");
    public static final List<String> SHARED_FILES = List.of("session_index.jsonl");

    private final AppPaths paths;
    private final boolean nativeLinks;

    public LinkService(AppPaths paths) {
        this(paths, true);
    }

    LinkService(AppPaths paths, boolean nativeLinks) {
        this.paths = paths;
        this.nativeLinks = nativeLinks;
    }

    public void ensureSharedLinks(Path accountHome) throws IOException {
        for (String name : SHARED_DIRS) {
            newSharedDirectoryLink(accountHome, name);
        }
        for (String name : SHARED_FILES) {
            newSharedFileLink(accountHome, name);
        }
    }

    private void newSharedDirectoryLink(Path accountHome, String name) throws IOException {
        Path target = paths.sharedHome().resolve(name);
        Path link = accountHome.resolve(name);
        Files.createDirectories(target);
        if (!nativeLinks) {
            Files.createDirectories(link);
            return;
        }
        if (isLinkToTarget(link, target)) {
            return;
        }

        backupExistingItem(link, accountHome);
        if (isWindows()) {
            createJunction(link, target);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private void newSharedFileLink(Path accountHome, String name) throws IOException {
        Path target = paths.sharedHome().resolve(name);
        Path link = accountHome.resolve(name);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            Files.createFile(target);
        }
        if (!nativeLinks) {
            if (!Files.exists(link)) {
                Files.createFile(link);
            }
            return;
        }
        if (isLinkToTarget(link, target)) {
            return;
        }

        backupExistingItem(link, accountHome);
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            Files.createLink(link, target);
        }
    }

    private boolean isLinkToTarget(Path link, Path target) {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        try {
            if (Files.isSymbolicLink(link)) {
                Path readTarget = Files.readSymbolicLink(link);
                Path normalizedTarget = readTarget.isAbsolute() ? readTarget : link.getParent().resolve(readTarget);
                return normalize(normalizedTarget).equals(normalize(target));
            }
            return Files.isSameFile(link, target);
        } catch (IOException ex) {
            return false;
        }
    }

    private void backupExistingItem(Path path, Path accountHome) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path backupRoot = accountHome.resolve(".isolated-backup-" + TimeNames.timestamp());
        Files.createDirectories(backupRoot);
        Path destination = uniqueDestination(backupRoot.resolve(path.getFileName()));
        Files.move(path, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path uniqueDestination(Path destination) {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return destination;
        }
        String name = destination.getFileName().toString();
        Path parent = destination.getParent();
        for (int index = 1; ; index++) {
            Path candidate = parent.resolve(name + "." + index);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }

    private void createJunction(Path link, Path target) throws IOException {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        byte[] outputBytes;
        try {
            outputBytes = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(outputBytes, StandardCharsets.UTF_8);
                throw new IOException("mklink /J failed with exit code " + exitCode + ": " + output);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating junction", ex);
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
