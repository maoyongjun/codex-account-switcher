package com.juchat.codexswitcher.service;

import com.juchat.codexswitcher.model.AccountSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRepositoryTest {
    @TempDir
    Path userHome;

    @Test
    void prepareSlotCreatesDefaultConfigImportsLegacyAuthAndInitializesSharedStore() throws Exception {
        Files.createDirectories(userHome.resolve(".codex"));
        Files.writeString(userHome.resolve(".codex").resolve("auth_user1.json"),
                TestTokens.authJson("legacy@example.com", 1893456000L), StandardCharsets.UTF_8);

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
        assertTrue(Files.exists(userHome.resolve(".codex-shared").resolve("state_5.sqlite")));
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
        assertTrue(Files.exists(userHome.resolve(".codex").resolve("state_5.sqlite")));
    }

    @Test
    void activateSlotForDefaultCodexHomeBackfillsDesktopConversationState() throws Exception {
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
        assertTrue(state.contains("\"projectless-thread-ids\":[\"" + recentId + "\",\"existing\"]"));
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
        assertTrue(index.contains(archivedId));
        assertTrue(index.contains("\"thread_name\":\"Archived 2026-03-05T02:55:51.000Z\""));
    }

    private static AccountRepository repository(Path userHome) {
        AppPaths paths = new AppPaths(userHome);
        return new AccountRepository(paths, new AuthTokenParser(), new LinkService(paths, false));
    }
}
