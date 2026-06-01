package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.AccountSummary;
import com.juchat.codexswitcher.model.AuthSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class AccountRepository {
    public static final int MAX_ACCOUNTS = 20;

    private final AppPaths paths;
    private final AuthTokenParser authTokenParser;
    private final LinkService linkService;

    public AccountRepository(AppPaths paths, AuthTokenParser authTokenParser, LinkService linkService) {
        this.paths = paths;
        this.authTokenParser = authTokenParser;
        this.linkService = linkService;
    }

    public List<AccountSummary> listAccounts() {
        List<AccountSummary> accounts = new ArrayList<>();
        for (int slot = 1; slot <= MAX_ACCOUNTS; slot++) {
            accounts.add(getSummary(slot));
        }
        return accounts;
    }

    public AccountSummary getSummary(int slot) {
        validateSlot(slot);
        Path accountHome = paths.accountHome(slot);
        Path auth = getAuthPath(accountHome);
        if (!Files.exists(auth)) {
            Path legacyAuth = getLegacyAuthPath(slot);
            if (Files.exists(legacyAuth)) {
                auth = legacyAuth;
            }
        }
        AuthSummary summary = authTokenParser.parse(auth);
        return new AccountSummary(slot, accountHome, summary.getEmail(), summary.getExpires(),
                summary.isPresent(), Files.isDirectory(accountHome));
    }

    public Path prepareSlot(int slot) throws IOException {
        validateSlot(slot);
        Path accountHome = paths.accountHome(slot);
        Files.createDirectories(accountHome);
        initializeSharedStore();
        importLegacyAuthIfNewer(slot, accountHome);
        ensureConfig(accountHome);
        linkService.ensureSharedLinks(accountHome);
        linkService.resetDerivedState(accountHome);
        Files.writeString(accountHome.resolve("account_slot.txt"), String.valueOf(slot), StandardCharsets.US_ASCII);
        return accountHome;
    }

    public void activateSlotForDefaultCodexHome(int slot) throws IOException {
        validateSlot(slot);
        Path accountHome = prepareSlot(slot);
        Path legacyHome = paths.legacyHome();
        Files.createDirectories(legacyHome);

        copyOrDeleteAuth(getAuthPath(accountHome), legacyHome.resolve("auth.json"));
        copyIfExists(getConfigPath(accountHome), legacyHome.resolve("config.toml"));
        linkService.ensureSharedLinks(legacyHome);
        linkService.resetDerivedState(legacyHome);
        CodexDesktopStateSync.syncRecentThreads(accountHome, legacyHome);
        Files.writeString(legacyHome.resolve("active_account_slot.txt"), String.valueOf(slot), StandardCharsets.US_ASCII);
    }

    public void prepareAll() throws IOException {
        for (int slot = 1; slot <= MAX_ACCOUNTS; slot++) {
            prepareSlot(slot);
        }
    }

    public void clearSlotAuthentication(int slot) throws IOException {
        validateSlot(slot);
        Path accountHome = paths.accountHome(slot);
        Files.deleteIfExists(getAuthPath(accountHome));
        Files.deleteIfExists(getLegacyAuthPath(slot));

        Path legacyHome = paths.legacyHome();
        if (isActiveSlot(slot)) {
            Files.deleteIfExists(legacyHome.resolve("auth.json"));
        }
    }

    public Path getAuthPath(Path accountHome) {
        return accountHome.resolve("auth.json");
    }

    public Path getLegacyAuthPath(int slot) {
        return paths.legacyHome().resolve("auth_user" + slot + ".json");
    }

    public Path getConfigPath(Path accountHome) {
        return accountHome.resolve("config.toml");
    }

    private void initializeSharedStore() throws IOException {
        Files.createDirectories(paths.sharedHome());
        for (String name : LinkService.SHARED_DIRS) {
            Path source = paths.legacyHome().resolve(name);
            Path destination = paths.sharedHome().resolve(name);
            if (!Files.exists(destination)) {
                if (Files.exists(source)) {
                    copyDirectory(source, destination);
                } else {
                    Files.createDirectories(destination);
                }
            }
        }

        for (String name : LinkService.SHARED_FILES) {
            Path source = paths.legacyHome().resolve(name);
            Path destination = paths.sharedHome().resolve(name);
            if (!Files.exists(destination)) {
                Files.createDirectories(destination.getParent());
                if (Files.exists(source)) {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.createFile(destination);
                }
            }
        }
        linkService.resetDerivedState(paths.sharedHome());
    }

    private void importLegacyAuthIfNewer(int slot, Path accountHome) throws IOException {
        Path legacyAuth = getLegacyAuthPath(slot);
        Path accountAuth = getAuthPath(accountHome);
        if (!Files.exists(legacyAuth)) {
            return;
        }
        if (!Files.exists(accountAuth) || Files.getLastModifiedTime(legacyAuth).compareTo(Files.getLastModifiedTime(accountAuth)) > 0) {
            Files.copy(legacyAuth, accountAuth, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void copyIfExists(Path source, Path destination) throws IOException {
        if (Files.exists(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void copyOrDeleteAuth(Path source, Path destination) throws IOException {
        if (Files.exists(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            Files.deleteIfExists(destination);
        }
    }

    private boolean isActiveSlot(int slot) throws IOException {
        Path activeSlotPath = paths.legacyHome().resolve("active_account_slot.txt");
        if (!Files.exists(activeSlotPath)) {
            return false;
        }
        String activeSlot = Files.readString(activeSlotPath, StandardCharsets.US_ASCII).trim();
        return activeSlot.equals(String.valueOf(slot));
    }

    private void ensureConfig(Path accountHome) throws IOException {
        Path legacyConfig = paths.legacyHome().resolve("config.toml");
        Path accountConfig = getConfigPath(accountHome);
        if (Files.exists(legacyConfig)) {
            if (!Files.exists(accountConfig)
                    || Files.getLastModifiedTime(legacyConfig).compareTo(Files.getLastModifiedTime(accountConfig)) > 0) {
                Files.copy(legacyConfig, accountConfig, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return;
            }
        }

        if (!Files.exists(accountConfig)) {
            String defaultConfig = String.join(System.lineSeparator(),
                    "cli_auth_credentials_store = \"file\"",
                    "",
                    "[windows]",
                    "sandbox = \"unelevated\"",
                    "");
            Files.writeString(accountConfig, defaultConfig, StandardCharsets.UTF_8);
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = destination.resolve(source.relativize(dir));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink()) {
                    Path target = destination.resolve(source.relativize(file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void validateSlot(int slot) {
        if (slot < 1 || slot > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("Account slot must be between 1 and " + MAX_ACCOUNTS + ".");
        }
    }
}
