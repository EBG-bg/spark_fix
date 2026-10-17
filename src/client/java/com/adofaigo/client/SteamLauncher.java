package com.adofaigo.client;

import com.adofaigo.AdofoigoMod;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import dev.codex.spark_fix.SparkFixConfig;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Starts ADOFAI through Steam and restores its window to the foreground on Windows. */
public final class SteamLauncher {
    private static final String GAME_ID = "977950";
    private static final long REGISTRY_QUERY_TIMEOUT_MILLIS = 1_500L;
    private static final int MAX_REGISTRY_OUTPUT_BYTES = 65_536;
    private static Thread launchThread;
    private static volatile LaunchStatus launchStatus = new LaunchStatus(Stage.IDLE, 0);

    public enum Stage { IDLE, COUNTDOWN, WAITING, STARTED, FAILED, TIMED_OUT, UNSUPPORTED }

    /** The deadline uses real time, independent of Minecraft's pause and tick rate. */
    public record LaunchStatus(Stage stage, long deadlineNanos) { }
    private SteamLauncher() {
    }

    public static synchronized void launchOrFocus() {
        if (launchThread != null) return;
        int delay = SparkFixConfig.adofaigoLaunchDelaySeconds();
        long launchAt = System.nanoTime() + delay * 1_000_000_000L;
        launchStatus = new LaunchStatus(delay > 0 ? Stage.COUNTDOWN : Stage.WAITING, launchAt);
        launchThread = Thread.ofVirtual().unstarted(() -> {
            try {
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                    finish(Stage.UNSUPPORTED);
                    return;
                }
                while (System.nanoTime() < launchAt) {
                    Thread.sleep(Math.min(100L, Math.max(1L, (launchAt - System.nanoTime()) / 1_000_000L)));
                }
                if (!updatePendingStatus(Stage.WAITING, 0)) return;
                Optional<ProcessHandle> game = findGameProcess();
                if (game.isEmpty()) launchFromSteam();
                long timeout = System.nanoTime() + 120_000_000_000L;
                while (System.nanoTime() < timeout) {
                    game = game.filter(ProcessHandle::isAlive).or(SteamLauncher::findGameProcess);
                    if (game.isPresent()) {
                        Optional<HWND> window = findMainWindow(game.get().pid());
                        if (window.isPresent()) {
                            finish(Stage.STARTED);
                            focusWindow(window.get());
                            return;
                        }
                    }
                    Thread.sleep(250L);
                }
                finish(Stage.TIMED_OUT);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception | LinkageError exception) {
                finish(Stage.FAILED);
                AdofoigoMod.LOGGER.warn("Could not start or focus ADOFAI", exception);
            } finally {
                synchronized (SteamLauncher.class) {
                    if (launchThread == Thread.currentThread()) launchThread = null;
                }
            }
        });
        launchThread.start();
    }

    public static synchronized void cancelPendingLaunch() {
        Thread pending = launchThread;
        launchThread = null;
        launchStatus = new LaunchStatus(Stage.IDLE, 0);
        if (pending != null) pending.interrupt();
    }

    public static LaunchStatus status() { return launchStatus; }

    private static void finish(Stage stage) {
        updatePendingStatus(stage, System.nanoTime()
                + (stage == Stage.STARTED ? 2_000_000_000L : 5_000_000_000L));
    }

    private static synchronized boolean updatePendingStatus(Stage stage, long deadline) {
        // A canceled worker must not overwrite the state of a subsequent launch.
        if (launchThread != Thread.currentThread() || launchThread.isInterrupted()) return false;
        launchStatus = new LaunchStatus(stage, deadline);
        return true;
    }

    private static Optional<ProcessHandle> findGameProcess() {
        try (var processes = ProcessHandle.allProcesses()) {
            return processes
                .filter(process -> process.info().command().map(SteamLauncher::looksLikeGame).orElse(false))
                .findFirst();
        }
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

    private static void launchFromSteam() throws IOException, InterruptedException {
        Path steam = findSteamExecutable().orElseThrow(
                () -> new IOException("Steam.exe was not found in the running processes, registry, or default folders"));
        AdofoigoMod.LOGGER.info("Launching ADOFAI through {}", steam);
        synchronized (SteamLauncher.class) {
            if (launchThread != Thread.currentThread() || launchThread.isInterrupted()) return;
            new ProcessBuilder(steam.toString(), "-applaunch", GAME_ID).start();
        }
    }

    private static Optional<Path> findSteamExecutable() throws InterruptedException {
        Optional<Path> runningSteam;
        try (var processes = ProcessHandle.allProcesses()) {
            runningSteam = processes
                .map(process -> process.info().command())
                .flatMap(Optional::stream)
                .map(SteamLauncher::pathOrNull)
                .filter(path -> path != null && isSteamExecutable(path))
                .findFirst();
        }
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

    private static Optional<Path> queryRegistry(String key, String valueName, boolean directoryValue)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            Charset consoleCharset = Charset.forName(
                    System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
            Process process = new ProcessBuilder("reg.exe", "query", key, "/v", valueName)
                    .redirectErrorStream(true)
                    .start();
            return readRegistryOutput(process, consoleCharset).stream().flatMap(String::lines)
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
        }
    }

    private static Optional<String> readRegistryOutput(Process process, Charset charset)
            throws IOException, InterruptedException {
        try {
            // A single registry value is small. Waiting first also bounds a producer
            // blocked by unexpectedly large output, before any blocking stream read.
            if (!process.waitFor(REGISTRY_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                AdofoigoMod.LOGGER.debug("Steam registry query timed out");
                return Optional.empty();
            }
            if (process.exitValue() != 0) return Optional.empty();
            byte[] output = process.getInputStream().readNBytes(MAX_REGISTRY_OUTPUT_BYTES + 1);
            if (output.length > MAX_REGISTRY_OUTPUT_BYTES) {
                AdofoigoMod.LOGGER.debug("Steam registry query returned too much output");
                return Optional.empty();
            }
            return Optional.of(new String(output, charset));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try (var output = process.getInputStream();
                 var error = process.getErrorStream();
                 var input = process.getOutputStream()) {
                // Close every pipe, including when another pipe fails to close.
            } catch (IOException exception) {
                AdofoigoMod.LOGGER.debug("Could not close Steam registry query streams", exception);
            }
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

    private static synchronized void focusWindow(HWND window) {
        if (launchThread != Thread.currentThread() || launchThread.isInterrupted()) return;
        try {
            User32.INSTANCE.ShowWindow(window, WinUser.SW_RESTORE);
            User32.INSTANCE.BringWindowToTop(window);
            if (!User32.INSTANCE.SetForegroundWindow(window)) {
                AdofoigoMod.LOGGER.debug("Windows declined the request to foreground ADOFAI");
            }
        } catch (RuntimeException exception) {
            AdofoigoMod.LOGGER.debug("Could not focus ADOFAI window", exception);
        }
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
