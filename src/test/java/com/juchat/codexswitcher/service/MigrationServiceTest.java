package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.RestoreMode;
import com.juchat.codexswitcher.model.RestoreResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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

        Path zip = userHome.resolve("codex-export.zip");
        fixture.migration.exportAll(zip);

        Properties manifest = fixture.migration.readManifest(zip);
        assertEquals("1", manifest.getProperty("schemaVersion"));
        assertEquals("1.0.0", manifest.getProperty("appVersion"));
        assertEquals("7", manifest.getProperty("maxAccounts"));
        assertEquals("plainZip", manifest.getProperty("exportSecurity"));
        assertEquals("true", manifest.getProperty("includesShared"));

        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            assertNotNull(zipFile.getEntry("accounts/account1/auth.json"));
            assertNotNull(zipFile.getEntry("shared/sessions/chat.jsonl"));
            assertFalse(zipFile.stream().anyMatch(entry -> entry.getName().startsWith("accounts/account1/sessions/")));
        }
    }

    @Test
    void restoreBacksUpExistingDataThenReplacesWithExportedContent() throws Exception {
        ServiceFixture fixture = fixture(userHome);
        fixture.repository.prepareSlot(1);
        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("restored@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                "restored-index", StandardCharsets.UTF_8);
        Path zip = userHome.resolve("codex-export.zip");
        fixture.migration.exportAll(zip);

        Files.writeString(userHome.resolve(".codex-account1").resolve("auth.json"),
                TestTokens.authJson("old@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                "old-index", StandardCharsets.UTF_8);

        RestoreResult result = fixture.migration.restore(zip, RestoreMode.BACKUP_THEN_REPLACE);

        assertTrue(Files.isDirectory(result.getBackupRoot()));
        assertTrue(Files.exists(result.getBackupRoot().resolve(".codex-account1").resolve("auth.json")));
        assertEquals("restored@example.com", new AuthTokenParser()
                .parse(userHome.resolve(".codex-account1").resolve("auth.json"))
                .getEmail());
        assertEquals("restored-index", Files.readString(userHome.resolve(".codex-shared").resolve("session_index.jsonl"),
                StandardCharsets.UTF_8));
    }

    private static ServiceFixture fixture(Path userHome) {
        AppPaths paths = new AppPaths(userHome);
        AccountRepository repository = new AccountRepository(paths, new AuthTokenParser(), new LinkService(paths, false));
        MigrationService migration = new MigrationService(paths, repository, () -> {
        });
        return new ServiceFixture(repository, migration);
    }

    private record ServiceFixture(AccountRepository repository, MigrationService migration) {
    }
}
