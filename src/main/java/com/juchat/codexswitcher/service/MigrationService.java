package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.RestoreMode;
import com.juchat.codexswitcher.model.RestoreResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MigrationService {
    public static final String APP_VERSION = "1.0.0";
    private static final String LEGACY_MAX_ACCOUNTS = "15";
    private static final String MANIFEST = "manifest.properties";
    private static final Set<String> ACCOUNT_TOP_LEVEL_SKIPS = sharedTopLevelNames();

    private final AppPaths paths;
    private final AccountRepository accountRepository;
    private final Runnable stopProcesses;

    public MigrationService(AppPaths paths, AccountRepository accountRepository, ProcessManager processManager) {
        this(paths, accountRepository, processManager::stopCursorAndCodex);
    }

    MigrationService(AppPaths paths, AccountRepository accountRepository, Runnable stopProcesses) {
        this.paths = paths;
        this.accountRepository = accountRepository;
        this.stopProcesses = stopProcesses;
    }

    public Path exportAll(Path zipPath) throws IOException {
        accountRepository.prepareAll();
        Path normalizedZip = zipPath.toAbsolutePath().normalize();
        Path parent = normalizedZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Set<String> writtenEntries = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(normalizedZip), StandardCharsets.UTF_8)) {
            addManifest(zip, writtenEntries);
            for (int slot = 1; slot <= AccountRepository.MAX_ACCOUNTS; slot++) {
                addTree(zip, writtenEntries, paths.accountHome(slot), "accounts/account" + slot + "/", ACCOUNT_TOP_LEVEL_SKIPS);
            }
            addTree(zip, writtenEntries, paths.sharedHome(), "shared/", Set.copyOf(LinkService.DERIVED_CACHE_FILES));
            addLegacyFiles(zip, writtenEntries);
        }
        return normalizedZip;
    }

    public RestoreResult restore(Path zipPath, RestoreMode mode) throws IOException {
        if (mode != RestoreMode.BACKUP_THEN_REPLACE) {
            throw new IllegalArgumentException("Unsupported restore mode: " + mode);
        }
        validateManifest(zipPath);
        stopProcesses.run();
        Path backupRoot = backupExistingData();
        extract(zipPath);
        accountRepository.prepareAll();
        return new RestoreResult(backupRoot);
    }

    public Properties readManifest(Path zipPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.getEntry(MANIFEST);
            if (entry == null) {
                throw new IOException("Missing manifest.properties.");
            }
            Properties properties = new Properties();
            try (InputStream input = zipFile.getInputStream(entry)) {
                properties.load(input);
            }
            return properties;
        }
    }

    private void addManifest(ZipOutputStream zip, Set<String> writtenEntries) throws IOException {
        String content = String.join("\n",
                "schemaVersion=1",
                "appVersion=" + APP_VERSION,
                "createdAt=" + OffsetDateTime.now(),
                "maxAccounts=" + AccountRepository.MAX_ACCOUNTS,
                "exportSecurity=plainZip",
                "includesShared=true",
                "");
        addBytes(zip, writtenEntries, MANIFEST, content.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
    }

    private void addLegacyFiles(ZipOutputStream zip, Set<String> writtenEntries) throws IOException {
        Path legacyConfig = paths.legacyHome().resolve("config.toml");
        if (Files.exists(legacyConfig)) {
            addFile(zip, writtenEntries, legacyConfig, "legacy/config.toml");
        }
        for (int slot = 1; slot <= AccountRepository.MAX_ACCOUNTS; slot++) {
            Path legacyAuth = paths.legacyHome().resolve("auth_user" + slot + ".json");
            if (Files.exists(legacyAuth)) {
                addFile(zip, writtenEntries, legacyAuth, "legacy/auth_user" + slot + ".json");
            }
        }
    }

    private void addTree(ZipOutputStream zip, Set<String> writtenEntries, Path root, String prefix, Set<String> topLevelSkips)
            throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        addDirectory(zip, writtenEntries, prefix);
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root)) {
                    Path relative = root.relativize(dir);
                    if (shouldSkipTopLevel(relative, topLevelSkips) || attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    addDirectory(zip, writtenEntries, prefix + toZipName(relative) + "/");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = root.relativize(file);
                if (!shouldSkipTopLevel(relative, topLevelSkips) && !attrs.isSymbolicLink()) {
                    addFile(zip, writtenEntries, file, prefix + toZipName(relative));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean shouldSkipTopLevel(Path relative, Set<String> topLevelSkips) {
        return relative.getNameCount() >= 1 && topLevelSkips.contains(relative.getName(0).toString());
    }

    private void addFile(ZipOutputStream zip, Set<String> writtenEntries, Path file, String entryName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Files.copy(file, bytes);
        addBytes(zip, writtenEntries, entryName, bytes.toByteArray(), Files.getLastModifiedTime(file).toMillis());
    }

    private void addDirectory(ZipOutputStream zip, Set<String> writtenEntries, String entryName) throws IOException {
        String name = entryName.endsWith("/") ? entryName : entryName + "/";
        if (writtenEntries.add(name)) {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(System.currentTimeMillis());
            zip.putNextEntry(entry);
            zip.closeEntry();
        }
    }

    private void addBytes(ZipOutputStream zip, Set<String> writtenEntries, String entryName, byte[] bytes, long timeMillis)
            throws IOException {
        if (!writtenEntries.add(entryName)) {
            return;
        }
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(timeMillis);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void validateManifest(Path zipPath) throws IOException {
        Properties manifest = readManifest(zipPath);
        requireManifestValue(manifest, "schemaVersion", "1");
        requireSupportedMaxAccounts(manifest.getProperty("maxAccounts"));
        requireManifestValue(manifest, "exportSecurity", "plainZip");
        requireManifestValue(manifest, "includesShared", "true");
    }

    private static void requireSupportedMaxAccounts(String actual) throws IOException {
        String current = String.valueOf(AccountRepository.MAX_ACCOUNTS);
        if (!current.equals(actual) && !LEGACY_MAX_ACCOUNTS.equals(actual)) {
            throw new IOException("Invalid manifest maxAccounts: expected " + current + " or "
                    + LEGACY_MAX_ACCOUNTS + ", actual " + actual);
        }
    }

    private static void requireManifestValue(Properties manifest, String key, String expected) throws IOException {
        String actual = manifest.getProperty(key);
        if (!expected.equals(actual)) {
            throw new IOException("Invalid manifest " + key + ": expected " + expected + ", actual " + actual);
        }
    }

    private Path backupExistingData() throws IOException {
        Path backupRoot = uniqueDestination(paths.restoreBackupRoot().resolve(TimeNames.timestamp()));
        Files.createDirectories(backupRoot);
        for (int slot = 1; slot <= AccountRepository.MAX_ACCOUNTS; slot++) {
            moveIfExists(paths.accountHome(slot), backupRoot.resolve(".codex-account" + slot));
        }
        moveIfExists(paths.sharedHome(), backupRoot.resolve(".codex-shared"));

        Path legacyBackup = backupRoot.resolve(".codex");
        for (int slot = 1; slot <= AccountRepository.MAX_ACCOUNTS; slot++) {
            moveLegacyFileIfExists(paths.legacyHome().resolve("auth_user" + slot + ".json"), legacyBackup);
        }
        moveLegacyFileIfExists(paths.legacyHome().resolve("config.toml"), legacyBackup);
        return backupRoot;
    }

    private void moveLegacyFileIfExists(Path file, Path legacyBackup) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(legacyBackup);
            Files.move(file, legacyBackup.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void moveIfExists(Path source, Path destination) throws IOException {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path uniqueDestination(Path destination) {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return destination;
        }
        Path parent = destination.getParent();
        String name = destination.getFileName().toString();
        for (int index = 1; ; index++) {
            Path candidate = parent.resolve(name + "." + index);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }

    private void extract(Path zipPath) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(MANIFEST)) {
                    continue;
                }
                Path target = mapEntryTarget(name);
                if (target == null) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path mapEntryTarget(String entryName) throws IOException {
        if (entryName.startsWith("accounts/account")) {
            int afterPrefix = "accounts/account".length();
            int slash = entryName.indexOf('/', afterPrefix);
            if (slash < 0) {
                return null;
            }
            int slot;
            try {
                slot = Integer.parseInt(entryName.substring(afterPrefix, slash));
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid account entry: " + entryName, ex);
            }
            if (slot < 1 || slot > AccountRepository.MAX_ACCOUNTS) {
                throw new IOException("Invalid account slot in entry: " + entryName);
            }
            String relative = entryName.substring(slash + 1);
            return safeResolve(paths.accountHome(slot), relative);
        }
        if (entryName.startsWith("shared/")) {
            return safeResolve(paths.sharedHome(), entryName.substring("shared/".length()));
        }
        if (entryName.startsWith("legacy/")) {
            return safeResolve(paths.legacyHome(), entryName.substring("legacy/".length()));
        }
        return null;
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (relative == null || relative.isBlank()) {
            return normalizedRoot;
        }
        Path target = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Unsafe zip entry path: " + relative);
        }
        return target;
    }

    private static String toZipName(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static Set<String> sharedTopLevelNames() {
        Set<String> names = LinkService.SHARED_DIRS.stream().collect(Collectors.toSet());
        names.addAll(LinkService.SHARED_FILES);
        names.addAll(LinkService.DERIVED_CACHE_FILES);
        return Set.copyOf(names);
    }
}
