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
}
