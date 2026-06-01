package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.AccountSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRepositoryTest {
    @TempDir
    Path userHome;

    @Test
    void prepareSlotCreatesDefaultConfigImportsLegacyAuthAndInitializesSharedStore() throws Exception {
        Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(userHome.resolve(".codex").resolve("auth_user1.json"),
                TestTokens.authJson("legacy@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.createDirectories(userHome.resolve(".codex-shared"));
        Files.writeString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                "stale-shared-cache", StandardCharsets.UTF_8);

        AccountRepository repository = repository(userHome);
        Path accountHome = repository.prepareSlot(1);

        assertTrue(Files.isDirectory(accountHome));
        assertTrue(Files.exists(accountHome.resolve("auth.json")));
        assertTrue(Files.readString(accountHome.resolve("config.toml"), StandardCharsets.UTF_8)
                .contains("cli_auth_credentials_store = \"file\""));
        assertEquals("1", Files.readString(accountHome.resolve("account_slot.txt"), StandardCharsets.US_ASCII));
        assertTrue(Files.isDirectory(userHome.resolve(".codex-shared").resolve("sessions")));
        assertTrue(Files.isDirectory(userHome.resolve(".codex-shared").resolve("archived_sessions")));
        assertTrue(Files.isDirectory(userHome.resolve(".codex-shared").resolve("sqlite")));
        assertTrue(Files.exists(userHome.resolve(".codex-shared").resolve("session_index.jsonl")));
        assertTrue(Files.exists(userHome.resolve(".codex-shared").resolve("logs_2.sqlite")));
        assertEquals("stale-shared-cache", Files.readString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                StandardCharsets.UTF_8));
    }

    @Test
    void listAccountsAlwaysReturnsTwentySlotsWithAuthSummary() throws Exception {
        Files.createDirectories(userHome.resolve(".codex-account3"));
        Files.writeString(userHome.resolve(".codex-account3").resolve("auth.json"),
                TestTokens.authJson("slot3@example.com", 1893456000L), StandardCharsets.UTF_8);

        List<AccountSummary> accounts = repository(userHome).listAccounts();

        assertEquals(AccountRepository.MAX_ACCOUNTS, accounts.size());
        AccountSummary slot3 = accounts.get(2);
        assertEquals(3, slot3.getSlot());
        assertEquals("slot3@example.com", slot3.getEmail());
        assertTrue(slot3.isPrepared());
    }

    @Test
    void activateSlotForDefaultCodexHomeCopiesAuthConfigAndMarker() throws Exception {
        Files.createDirectories(userHome.resolve(".codex-account2"));
        Files.writeString(userHome.resolve(".codex-account2").resolve("auth.json"),
                TestTokens.authJson("slot2@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(userHome.resolve(".codex-account2").resolve("config.toml"),
                "model = \"gpt-5.5\"", StandardCharsets.UTF_8);

        repository(userHome).activateSlotForDefaultCodexHome(2);

        assertTrue(Files.exists(userHome.resolve(".codex").resolve("auth.json")));
        assertEquals("2", Files.readString(userHome.resolve(".codex").resolve("active_account_slot.txt"),
                StandardCharsets.US_ASCII));
        assertTrue(Files.readString(userHome.resolve(".codex").resolve("config.toml"), StandardCharsets.UTF_8)
                .contains("gpt-5.5"));
    }

    @Test
    void clearSlotAuthenticationRemovesOnlyAuthFilesAndPreservesSlotData() throws Exception {
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account7"));
        Files.writeString(accountHome.resolve("auth.json"),
                TestTokens.authJson("slot7@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(accountHome.resolve("config.toml"), "model = \"gpt-5.5\"", StandardCharsets.UTF_8);
        Files.createDirectories(accountHome.resolve("sessions"));
        Files.writeString(accountHome.resolve("session_index.jsonl"), "{\"id\":\"session\"}", StandardCharsets.UTF_8);
        Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(userHome.resolve(".codex").resolve("auth_user7.json"),
                TestTokens.authJson("legacy7@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.createDirectories(userHome.resolve(".codex-shared").resolve("sessions"));

        repository(userHome).clearSlotAuthentication(7);

        assertFalse(Files.exists(accountHome.resolve("auth.json")));
        assertFalse(Files.exists(userHome.resolve(".codex").resolve("auth_user7.json")));
        assertTrue(Files.isDirectory(accountHome));
        assertEquals("model = \"gpt-5.5\"",
                Files.readString(accountHome.resolve("config.toml"), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(accountHome.resolve("sessions")));
        assertTrue(Files.exists(accountHome.resolve("session_index.jsonl")));
        assertTrue(Files.isDirectory(userHome.resolve(".codex-shared").resolve("sessions")));
    }

    @Test
    void clearSlotAuthenticationRemovesDefaultAuthForActiveSlot() throws Exception {
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account8"));
        Files.writeString(accountHome.resolve("auth.json"),
                TestTokens.authJson("slot8@example.com", 1893456000L), StandardCharsets.UTF_8);
        Path legacyHome = Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(legacyHome.resolve("auth.json"),
                TestTokens.authJson("default@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(legacyHome.resolve("active_account_slot.txt"), "8", StandardCharsets.US_ASCII);

        repository(userHome).clearSlotAuthentication(8);

        assertFalse(Files.exists(accountHome.resolve("auth.json")));
        assertFalse(Files.exists(legacyHome.resolve("auth.json")));
    }

    @Test
    void activateSlotForDefaultCodexHomeClearsDefaultAuthWhenSlotHasNoAuth() throws Exception {
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account9"));
        Files.writeString(accountHome.resolve("config.toml"), "model = \"gpt-5.5\"", StandardCharsets.UTF_8);
        Path legacyHome = Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(legacyHome.resolve("auth.json"),
                TestTokens.authJson("old-default@example.com", 1893456000L), StandardCharsets.UTF_8);

        repository(userHome).activateSlotForDefaultCodexHome(9);

        assertFalse(Files.exists(legacyHome.resolve("auth.json")));
        assertEquals("9", Files.readString(legacyHome.resolve("active_account_slot.txt"),
                StandardCharsets.US_ASCII));
        assertTrue(Files.readString(legacyHome.resolve("config.toml"), StandardCharsets.UTF_8)
                .contains("gpt-5.5"));
    }

    @Test
    void activateSlotForDefaultCodexHomeBackfillsDesktopWorkspaceHints() throws Exception {
        Path workspace = Files.createDirectories(userHome.resolve("workspace"));
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account4"));
        Files.writeString(accountHome.resolve("auth.json"),
                TestTokens.authJson("slot4@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(accountHome.resolve("config.toml"), "model = \"gpt-5.5\"", StandardCharsets.UTF_8);
        Path sessions = Files.createDirectories(accountHome.resolve("sessions").resolve("2026").resolve("05").resolve("12"));
        String recentId = "019e1b10-1eb7-76b1-a5a2-86549052b627";
        Files.writeString(accountHome.resolve("session_index.jsonl"),
                "{\"id\":\"" + recentId + "\",\"thread_name\":\"recent\",\"updated_at\":\"2026-05-12T07:21:46Z\"}",
                StandardCharsets.UTF_8);
        Files.writeString(sessions.resolve("rollout-2026-05-12T15-21-41-" + recentId + ".jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + recentId + "\",\"cwd\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}}",
                StandardCharsets.UTF_8);
        Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(userHome.resolve(".codex").resolve(".codex-global-state.json"),
                "{\"projectless-thread-ids\":[\"existing\"],\"thread-workspace-root-hints\":{\"existing\":\"C:\\\\old\"}}",
                StandardCharsets.UTF_8);

        repository(userHome).activateSlotForDefaultCodexHome(4);

        String state = Files.readString(userHome.resolve(".codex").resolve(".codex-global-state.json"),
                StandardCharsets.UTF_8);
        assertTrue(state.contains(recentId));
        assertTrue(state.contains("\"projectless-thread-ids\":[\"existing\"]"));
        assertTrue(state.contains(workspace.toAbsolutePath().normalize().toString().replace("\\", "\\\\")));
        assertEquals(state, Files.readString(userHome.resolve(".codex").resolve(".codex-global-state.json.bak"),
                StandardCharsets.UTF_8));
    }

    @Test
    void activateSlotForDefaultCodexHomeIndexesArchivedSessions() throws Exception {
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account5"));
        Files.writeString(accountHome.resolve("auth.json"),
                TestTokens.authJson("slot5@example.com", 1893456000L), StandardCharsets.UTF_8);
        Path archivedSessions = Files.createDirectories(accountHome.resolve("archived_sessions"));
        String archivedId = "019cbbec-4bfa-71a1-8f45-d0a0a3221827";
        Files.writeString(archivedSessions.resolve("rollout-2026-03-05T10-55-51-" + archivedId + ".jsonl"),
                "{\"timestamp\":\"2026-03-05T02:55:51.000Z\",\"type\":\"session_meta\",\"payload\":{\"id\":\""
                        + archivedId + "\",\"cwd\":\"C:\\\\workspace\"}}",
                StandardCharsets.UTF_8);

        repository(userHome).activateSlotForDefaultCodexHome(5);

        String index = Files.readString(userHome.resolve(".codex-account5").resolve("session_index.jsonl"),
                StandardCharsets.UTF_8);
        String archivedSession = Files.readString(archivedSessions.resolve("rollout-2026-03-05T10-55-51-"
                + archivedId + ".jsonl"), StandardCharsets.UTF_8);
        assertTrue(index.contains(archivedId));
        assertTrue(index.contains("\"thread_name\":\"Archived 2026-03-05T02:55:51.000Z\""));
        assertTrue(archivedSession.contains("\"thread_source\":\"user\""));
    }

    @Test
    void activateSlotForDefaultCodexHomePreservesDerivedStateCaches() throws Exception {
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account6"));
        Files.writeString(accountHome.resolve("auth.json"),
                TestTokens.authJson("slot6@example.com", 1893456000L), StandardCharsets.UTF_8);
        Files.writeString(accountHome.resolve("state_5.sqlite"), "account-cache", StandardCharsets.UTF_8);

        Path desktopHome = Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(desktopHome.resolve("state_5.sqlite"), "desktop-cache", StandardCharsets.UTF_8);
        Files.createDirectories(userHome.resolve(".codex-shared"));
        Files.writeString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                "shared-cache", StandardCharsets.UTF_8);

        repository(userHome).activateSlotForDefaultCodexHome(6);

        assertEquals("account-cache", Files.readString(accountHome.resolve("state_5.sqlite"), StandardCharsets.UTF_8));
        assertEquals("desktop-cache", Files.readString(desktopHome.resolve("state_5.sqlite"), StandardCharsets.UTF_8));
        assertEquals("shared-cache", Files.readString(userHome.resolve(".codex-shared").resolve("state_5.sqlite"),
                StandardCharsets.UTF_8));
    }

    private static AccountRepository repository(Path userHome) {
        AppPaths paths = new AppPaths(userHome);
        return new AccountRepository(paths, new AuthTokenParser(), new LinkService(paths, false));
    }
}
