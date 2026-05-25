package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.RestoreMode;
import com.juchat.codexswitcher.model.RestoreResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationServiceTest {
    @TempDir
    Path userHome;

    @Test
    void exportWritesManifestAccountsSharedAndSkipsAccountSharedEntries() throws Exception {
        ServiceFixture fixture = fixture(userHome);
        fixture.repository.prepareSlot(1);
        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("export@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("sessions").resolve("chat.jsonl"),
                "shared-chat", StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("logs_2.sqlite"),
                "shared-logs", StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                "shared-state-cache", StandardCharsets.UTF_8);

        Path zip = userHome.resolve("codex-export.zip");
        fixture.migration.exportAll(zip);

        Properties manifest = fixture.migration.readManifest(zip);
        assertEquals("1", manifest.getProperty("schemaVersion"));
        assertEquals("1.0.0", manifest.getProperty("appVersion"));
        assertEquals("20", manifest.getProperty("maxAccounts"));
        assertEquals("plainZip", manifest.getProperty("exportSecurity"));
        assertEquals("true", manifest.getProperty("includesShared"));

        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            assertNotNull(zipFile.getEntry("accounts/account1/auth.json"));
            assertNotNull(zipFile.getEntry("shared/sessions/chat.jsonl"));
            assertNotNull(zipFile.getEntry("shared/logs_2.sqlite"));
            assertFalse(zipFile.stream().anyMatch(entry -> entry.getName().equals("shared/state_5.sqlite")));
            assertFalse(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("accounts/account1/sessions/")));
            assertFalse(zipFile.stream().anyMatch(entry -> entry.getName().equals("accounts/account1/logs_2.sqlite")));
        }
    }

    @Test
    void restoreAcceptsLegacyFifteenAccountManifest() throws Exception {
        ServiceFixture fixture = fixture(userHome);
        fixture.repository.prepareSlot(1);
        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("legacy-restore@example.com", 1893456000L), StandardCharsets.UTF_8);
        Path exported = userHome.resolve("codex-export.zip");
        fixture.migration.exportAll(exported);

        Path legacyZip = userHome.resolve("codex-export-legacy-15.zip");
        rewriteManifest(exported, legacyZip, "maxAccounts=20", "maxAccounts=15");

        RestoreResult result = fixture.migration.restore(legacyZip, RestoreMode.BACKUP_THEN_REPLACE);

        assertTrue(Files.isDirectory(result.getBackupRoot()));
        assertEquals("legacy-restore@example.com", new AuthTokenParser()
                .parse(userHome.resolve(".codex-account1").resolve("auth.json"))
                .getEmail());
    }

    @Test
    void restoreBacksUpExistingDataThenReplacesWithExportedContent() throws Exception {
        ServiceFixture fixture = fixture(userHome);
        fixture.repository.prepareSlot(1);
        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("restored@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                "restored-index", StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                "restored-state", StandardCharsets.UTF_8);
        Path zip = userHome.resolve("codex-export.zip");
        fixture.migration.exportAll(zip);

        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("old@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                "old-index", StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                "old-state", StandardCharsets.UTF_8);

        RestoreResult result = fixture.migration.restore(zip, RestoreMode.BACKUP_THEN_REPLACE);

        assertTrue(Files.isDirectory(result.getBackupRoot()));
        assertTrue(Files.exists(result.getBackupRoot().resolve(".codex-account1").resolve("auth.json")));
        assertEquals("restored@example.com", new AuthTokenParser()
                .parse(userHome.resolve(".codex-account1").resolve("auth.json"))
                .getEmail());
        assertEquals("restored-index", Files.readString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                StandardCharsets.UTF_8));
        assertFalse(Files.exists(userHome.resolve(".codex-shared").resolve("state_5.sqlite")));
    }

    private static ServiceFixture fixture(Path userHome) {
        AppPaths paths = new AppPaths(userHome);
        AccountRepository repository = new AccountRepository(paths, new AuthTokenParser(), new LinkService(paths, false));
        MigrationService migration = new MigrationService(paths, repository, () -> {
        });
        return new ServiceFixture(repository, migration);
    }

    private static void rewriteManifest(Path sourceZip, Path targetZip, String from, String to) throws Exception {
        Files.createDirectories(targetZip.getParent());
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(sourceZip), StandardCharsets.UTF_8);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(targetZip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    if ("manifest.properties".equals(entry.getName())) {
                        String manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                                .replace(from, to);
                        output.write(manifest.getBytes(StandardCharsets.UTF_8));
                    } else {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
    }

    private record ServiceFixture(AccountRepository repository, MigrationService migration) {
    }
}
