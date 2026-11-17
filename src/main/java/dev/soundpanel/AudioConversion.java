package dev.soundpanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Imports recognized local audio formats; network/playlist inputs are never passed to FFmpeg. */
public final class AudioConversion {
    static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 24L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of(
            "ogg", "oga", "opus", "mp3", "mp2", "wav", "wave", "flac", "aac", "m4a", "m4b",
            "mp4", "mov", "aiff", "aif", "aifc", "wma", "webm", "mka", "mkv",
            "ac3", "eac3", "au", "amr", "caf", "ape", "wv", "spx");
    private static final String FORMATS = String.join(",",
            "ogg", "mp3", "wav", "flac", "aac", "mov", "aiff", "asf", "matroska", "webm",
            "ac3", "eac3", "au", "amr", "caf", "ape", "wv");
    private AudioConversion() {}

    public static boolean isSupportedName(String name) {
        return EXTENSIONS.contains(extension(name));
    }

    public static boolean isOggName(String name) {
        String ext = extension(name);
        return ext.equals("ogg") || ext.equals("oga");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static void transcode(Path source, Path destination) throws IOException {
        if (source.getFileName() == null || !isSupportedName(source.getFileName().toString())) {
            throw new IOException("Choose a supported audio file.");
        }
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_INPUT_BYTES) {
            throw new IOException("Choose a local audio file up to 64 MB.");
        }

        Path ffmpeg = findFfmpeg();
        if (ffmpeg == null) {
            throw new IOException("To import this format, install FFmpeg with libvorbis and restart Minecraft, or use Ogg Vorbis.");
        }

        // Explicit audio-only demuxers and protocol whitelist exclude network URLs, concat scripts and playlists.
        // Use a private output file and move only after success, preserving the current mapping on failure.
        Path parent = destination.toAbsolutePath().getParent();
        Path output = Files.createTempFile(parent, "soundpanel-convert-", ".ogg");
        try {
            List<String> args = new ArrayList<>(List.of(ffmpeg.toString(),
                    "-nostdin", "-hide_banner", "-v", "error", "-xerror", "-y",
                    "-protocol_whitelist", "file",
                    "-format_whitelist", FORMATS, "-i", source.toAbsolutePath().toString(),
                    "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
                    "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5", "-t", "600",
                    "-fs", Long.toString(MAX_OUTPUT_BYTES), "-f", "ogg", output.toString()));
            Process process = new ProcessBuilder(args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(45, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Audio conversion timed out.");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Audio conversion was cancelled.", e);
            }
            if (process.exitValue() != 0 || Files.size(output) == 0) {
                throw new IOException("FFmpeg could not decode the audio or has no Vorbis encoder.");
            }
            Files.move(output, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static Path findFfmpeg() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String tool = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "ffmpeg.exe" : "ffmpeg";
        for (String root : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (root.length() > 2 && root.startsWith("\"") && root.endsWith("\"")) {
                root = root.substring(1, root.length() - 1);
            }
            try {
                Path directory = Path.of(root);
                if (!directory.isAbsolute()) continue;
                Path candidate = directory.resolve(tool);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            } catch (java.nio.file.InvalidPathException ignored) { }
        }
        return null;
    }
}
