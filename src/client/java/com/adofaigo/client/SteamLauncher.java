package com.adofaigo.client;

import com.adofaigo.AdofoigoMod;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Starts ADOFAI through Steam and restores its window to the foreground on Windows. */
public final class SteamLauncher {
    private static final String GAME_ID = "977950";
    private SteamLauncher() {
    }

    public static void launchOrFocus() {
        Thread.startVirtualThread(() -> {
            try {
                Optional<ProcessHandle> game = findGameProcess();
                if (game.isEmpty()) {
                    launchFromSteam();
                    game = waitForGame(60_000L);
                }
                game.ifPresent(SteamLauncher::focusWindow);
            } catch (Exception exception) {
                AdofoigoMod.LOGGER.warn("Could not start or focus ADOFAI", exception);
            }
        });
    }

    private static Optional<ProcessHandle> findGameProcess() {
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().command().map(SteamLauncher::looksLikeGame).orElse(false))
                .findFirst();
    }

    private static boolean looksLikeGame(String executablePath) {
        String executableName;
        try {
            executableName = Path.of(executablePath).getFileName().toString();
        } catch (RuntimeException exception) {
            executableName = executablePath;
        }
        String value = executableName.toLowerCase(Locale.ROOT);
        return value.equals("adofai.exe")
                || value.equals("adofai")
                || value.contains("a dance of fire and ice");
    }

    private static Optional<ProcessHandle> waitForGame(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        Optional<ProcessHandle> game;
        while (System.nanoTime() < deadline && (game = findGameProcess()).isEmpty()) {
            Thread.sleep(500L);
        }
        return findGameProcess();
    }

    private static void launchFromSteam() throws IOException {
        Path steam = findSteamExecutable().orElseThrow(
                () -> new IOException("Steam.exe was not found in the running processes, registry, or default folders"));
        AdofoigoMod.LOGGER.info("Launching ADOFAI through {}", steam);
        new ProcessBuilder(steam.toString(), "-applaunch", GAME_ID).start();
    }

    private static Optional<Path> findSteamExecutable() {
        Optional<Path> runningSteam = ProcessHandle.allProcesses()
                .map(process -> process.info().command())
                .flatMap(Optional::stream)
                .map(SteamLauncher::pathOrNull)
                .filter(path -> path != null && isSteamExecutable(path))
                .findFirst();
        if (runningSteam.isPresent()) {
            return runningSteam;
        }

        Optional<Path> userRegistry = queryRegistry(
                "HKCU\\Software\\Valve\\Steam", "SteamExe", false);
        if (userRegistry.isPresent()) {
            return userRegistry;
        }

        Optional<Path> machineRegistry = queryRegistry(
                "HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath", true);
        if (machineRegistry.isPresent()) {
            return machineRegistry;
        }

        List<Path> candidates = new ArrayList<>();
        addDefaultCandidate(candidates, System.getenv("ProgramFiles(x86)"));
        addDefaultCandidate(candidates, System.getenv("ProgramFiles"));
        return candidates.stream().filter(SteamLauncher::isSteamExecutable).findFirst();
    }

    private static Optional<Path> queryRegistry(String key, String valueName, boolean directoryValue) {
        try {
            Process process = new ProcessBuilder("reg.exe", "query", key, "/v", valueName)
                    .redirectErrorStream(true)
                    .start();
            Charset consoleCharset = Charset.forName(
                    System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
            String output = new String(process.getInputStream().readAllBytes(), consoleCharset);
            if (process.waitFor() != 0) {
                return Optional.empty();
            }
            return output.lines()
                    .filter(line -> line.contains("REG_SZ"))
                    .map(line -> line.substring(line.indexOf("REG_SZ") + "REG_SZ".length()).trim())
                    .map(SteamLauncher::pathOrNull)
                    .filter(path -> path != null)
                    .map(path -> directoryValue ? path.resolve("steam.exe") : path)
                    .filter(SteamLauncher::isSteamExecutable)
                    .findFirst();
        } catch (IOException exception) {
            AdofoigoMod.LOGGER.debug("Could not read Steam path from registry key {}", key, exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Path pathOrNull(String value) {
        try {
            return Path.of(value.replace('"', ' ').trim()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void addDefaultCandidate(List<Path> candidates, String programFiles) {
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Path.of(programFiles, "Steam", "steam.exe"));
        }
    }

    private static boolean isSteamExecutable(Path path) {
        Path fileName = path.getFileName();
        return fileName != null
                && fileName.toString().equalsIgnoreCase("steam.exe")
                && Files.isRegularFile(path);
    }

    private static void focusWindow(ProcessHandle process) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try {
            HWND window = waitForMainWindow(process.pid(), 30_000L).orElse(null);
            if (window == null) {
                AdofoigoMod.LOGGER.debug("ADOFAI process {} did not expose a visible window", process.pid());
                return;
            }
            User32.INSTANCE.ShowWindow(window, WinUser.SW_RESTORE);
            User32.INSTANCE.BringWindowToTop(window);
            if (!User32.INSTANCE.SetForegroundWindow(window)) {
                AdofoigoMod.LOGGER.debug("Windows declined the request to foreground ADOFAI process {}", process.pid());
            }
        } catch (RuntimeException exception) {
            AdofoigoMod.LOGGER.debug("Could not focus ADOFAI window", exception);
        }
    }

    private static Optional<HWND> waitForMainWindow(long processId, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        Optional<HWND> window;
        while (System.nanoTime() < deadline && (window = findMainWindow(processId)).isEmpty()) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return findMainWindow(processId);
    }

    private static Optional<HWND> findMainWindow(long processId) {
        AtomicReference<HWND> result = new AtomicReference<>();
        User32.INSTANCE.EnumWindows((window, data) -> {
            IntByReference windowProcessId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, windowProcessId);
            if (Integer.toUnsignedLong(windowProcessId.getValue()) == processId
                    && User32.INSTANCE.IsWindowVisible(window)) {
                result.set(window);
                return false;
            }
            return true;
        }, Pointer.NULL);
        return Optional.ofNullable(result.get());
    }
}
