package dev.soundpanel;

import com.sun.jna.platform.win32.Shell32;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Creates and opens the instance folder without AWT Desktop or shell command strings. */
final class FolderAccess {
    private FolderAccess() {}

    static Path sourceFor(Path gameDirectory) {
        return gameDirectory.resolve("sounds").toAbsolutePath().normalize();
    }

    static Path ensure(Path folder) throws IOException {
        return Files.createDirectories(folder.toAbsolutePath().normalize());
    }

    static void open(Path folder) throws IOException {
        Path ready = ensure(folder);
        var os = Util.getOperatingSystem();
        // Minecraft's opener swallows errors; use OS folder commands with observable startup first.
        // The Windows Shell API provides a real acceptance code without depending on AWT support.
        if (os == Util.OperatingSystem.UNKNOWN) {
            throw new IOException("This operating system has no known folder opener.");
        }
        launch(ready, os, path -> {
            var result = Shell32.INSTANCE.ShellExecute(null, "open", path.toString(), null, null, 1);
            return result == null ? 0 : result.longValue();
        }, FolderAccess::runCommand);
    }

    static void launch(Path folder, Util.OperatingSystem os, WindowsShell shell, Starter starter) throws IOException {
        IOException cause = null;
        for (Command command : commandOrder(folder, os)) {
            if (command.args().isEmpty()) {
                try {
                    long result = shell.open(folder);
                    if (result > 32) return;
                    cause = new IOException("Windows ShellExecute rejected the folder (code " + result + ").");
                } catch (RuntimeException | LinkageError failure) {
                    cause = new IOException("Windows ShellExecute could not open the folder.", failure);
                }
                continue;
            }
            try {
                starter.run(command.args(), command.checkExit());
                return;
            } catch (IOException failure) {
                if (cause != null) failure.addSuppressed(cause);
                cause = failure;
                if (Thread.currentThread().isInterrupted()) throw failure;
            }
        }
        if (cause == null) throw new IOException("No folder opener is available.");
        throw cause;
    }

    private static List<Command> commandOrder(Path path, Util.OperatingSystem os) {
        String arg = path.toString();
        return switch (os) {
            case WINDOWS -> List.of(new Command(List.of(), false),
                    new Command(List.of(explorerExe(), arg), false),
                    new Command(List.of("rundll32.exe", "url.dll,FileProtocolHandler", path.toUri().toASCIIString()), false));
            case OSX -> List.of(new Command(List.of("/usr/bin/open", arg), true));
            case LINUX, SOLARIS -> List.of(new Command(List.of("xdg-open", arg), true), new Command(List.of("gio", "open", arg), true));
            case UNKNOWN -> List.of();
        };
    }

    private static String explorerExe() {
        String windows = System.getenv("WINDIR");
        if (windows != null && !windows.isBlank()) {
            Path executable = Path.of(windows, "explorer.exe");
            if (Files.isRegularFile(executable)) return executable.toString();
        }
        return "explorer.exe";
    }

    private static void runCommand(List<String> args, boolean checkExit) throws IOException {
        Process process = new ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.getOutputStream().close();
        // Explorer may return immediately after dispatch; process exit is not a reliable Windows signal.
        if (!checkExit) return;
        try {
            if (process.waitFor(900, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                throw new IOException("Folder opener exited with code " + process.exitValue() + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Folder opening was interrupted.", interrupted);
        }
    }

    private record Command(List<String> args, boolean checkExit) {}
    @FunctionalInterface interface WindowsShell { long open(Path directory); }
    @FunctionalInterface interface Starter { void run(List<String> args, boolean checkExit) throws IOException; }
}
