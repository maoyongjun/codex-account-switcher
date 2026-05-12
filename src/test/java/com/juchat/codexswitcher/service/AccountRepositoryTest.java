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
        assertTrue(Files.exists(userHome.resolve(".codex-shared").resolve("session_index.jsonl")));
    }

    @Test
    void listAccountsAlwaysReturnsSevenSlotsWithAuthSummary() throws Exception {
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

    private static AccountRepository repository(Path userHome) {
        AppPaths paths = new AppPaths(userHome);
        return new AccountRepository(paths, new AuthTokenParser(), new LinkService(paths, false));
    }
}
