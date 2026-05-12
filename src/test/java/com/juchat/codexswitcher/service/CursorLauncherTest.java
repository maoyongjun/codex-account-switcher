package com.juchat.codexswitcher.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CursorLauncherTest {
    @TempDir
    Path userHome;

    @AfterEach
    void clearOverrides() {
        System.clearProperty("codex.switcher.cursorExe");
        System.clearProperty("codex.switcher.codexExe");
        System.clearProperty("codex.switcher.codexWorkspace");
    }

    @Test
    void findCodexPathUsesExplicitOverride() throws Exception {
        Path codex = userHome.resolve("codex.exe");
        Files.writeString(codex, "");
        System.setProperty("codex.switcher.codexExe", codex.toString());

        CursorLauncher launcher = new CursorLauncher(new AppPaths(userHome), new ProcessManager(0));

        assertEquals(codex.toAbsolutePath().normalize(), launcher.findCodexPath());
    }

    @Test
    void findCursorPathUsesExplicitOverride() throws Exception {
        Path cursor = userHome.resolve("Cursor.exe");
        Files.writeString(cursor, "");
        System.setProperty("codex.switcher.cursorExe", cursor.toString());

        CursorLauncher launcher = new CursorLauncher(new AppPaths(userHome), new ProcessManager(0));

        assertEquals(cursor.toAbsolutePath().normalize(), launcher.findCursorPath());
    }

    @Test
    void resolveCodexWorkspaceUsesExplicitOverride() throws Exception {
        Path workspace = Files.createDirectories(userHome.resolve("workspace"));
        System.setProperty("codex.switcher.codexWorkspace", workspace.toString());

        CursorLauncher launcher = new CursorLauncher(new AppPaths(userHome), new ProcessManager(0));

        assertEquals(workspace.toAbsolutePath().normalize(), launcher.resolveCodexWorkspace(userHome.resolve(".codex-account1")));
    }

    @Test
    void resolveCodexWorkspaceUsesLatestSessionCwd() throws Exception {
        Path workspace = Files.createDirectories(userHome.resolve("real-workspace"));
        Path accountHome = Files.createDirectories(userHome.resolve(".codex-account1"));
        Path sessions = Files.createDirectories(accountHome.resolve("sessions").resolve("2026").resolve("05").resolve("12"));
        String id = "019e1b10-1eb7-76b1-a5a2-86549052b627";
        Files.writeString(accountHome.resolve("session_index.jsonl"),
                "{\"id\":\"" + id + "\",\"thread_name\":\"recent\",\"updated_at\":\"2026-05-12T07:21:46Z\"}");
        Files.writeString(sessions.resolve("rollout-2026-05-12T15-21-41-" + id + ".jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"cwd\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}}");

        CursorLauncher launcher = new CursorLauncher(new AppPaths(userHome), new ProcessManager(0));

        assertEquals(workspace.toAbsolutePath().normalize(), launcher.resolveCodexWorkspace(accountHome));
    }
}
