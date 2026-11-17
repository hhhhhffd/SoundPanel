package dev.soundpanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class SoundConfig {
    public record Clip(String token, String name) {}
    private record Settings(Map<String, Clip> mappings) {}

    private static final int MAX_SIZE = 24 * 1024 * 1024;
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{32}");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path configDir;
    private final Path sourceDir;
    private volatile Map<String, Clip> mappings = Map.of();

    SoundConfig() { this(FabricLoader.getInstance().getConfigDir().resolve("soundpanel"), FabricLoader.getInstance().getGameDir().resolve("sounds")); }
    SoundConfig(Path configDir, Path sourceDir) { this.configDir = configDir; this.sourceDir = sourceDir; }
    Path importDir() { return configDir.resolve("clips"); }
    Path sourceDir() { return sourceDir; }

    synchronized void load() {
        Path manifest = configDir.resolve("sounds.json");
        if (!Files.isRegularFile(manifest)) return;
        try (var reader = Files.newBufferedReader(manifest)) {
            Settings stored = JSON.fromJson(reader, Settings.class);
            if (stored == null || stored.mappings() == null) return;
            var safe = new TreeMap<String, Clip>();
            stored.mappings().forEach((id, clip) -> {
                if (id != null && Identifier.tryParse(id) != null && clip != null
                        && clip.token() != null && TOKEN.matcher(clip.token()).matches()
                        && clip.name() != null) safe.put(id, clip);
            });
            mappings = Map.copyOf(safe);
        } catch (Exception e) {
            SoundPanel.LOG.warn("Unable to read Sound Panel settings", e);
        }
    }

    public Map<String, Clip> mappings() { return mappings; }
    public Clip clipFor(Identifier id) {
        Clip clip = mappings.get(id.toString());
        return clip != null && Files.isRegularFile(importDir().resolve(clip.token() + ".ogg"), LinkOption.NOFOLLOW_LINKS) ? clip : null;
    }

    synchronized Clip install(Identifier event, Path path) throws IOException {
        Clip clip = prepare(path, importDir());
        finish(event, clip);
        return clip;
    }

    Clip prepareCandidate(Path path) throws IOException { return prepare(path, previewDir()); }

    synchronized Clip installCandidate(Identifier event, Clip preview) throws IOException {
        if (preview == null || !TOKEN.matcher(preview.token()).matches()) throw new IOException("Choose an audio file first.");
        Path from = previewDir().resolve(preview.token() + ".ogg");
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Audio preview is no longer available.");
        Files.createDirectories(importDir());
        Clip clip = new Clip(token(), preview.name());
        try {
            copyBounded(from, importDir().resolve(clip.token() + ".ogg"));
            finish(event, clip);
            return clip;
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(importDir().resolve(clip.token() + ".ogg")); } catch (IOException ignored) {}
            throw e;
        }
    }

    private void finish(Identifier event, Clip clip) throws IOException {
        try {
            var updated = new TreeMap<>(mappings);
            updated.put(event.toString(), clip);
            save(updated);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(importDir().resolve(clip.token() + ".ogg")); } catch (IOException ignored) {}
            throw e;
        }
    }

    void discardCandidate(Clip clip) {
        if (clip == null || !TOKEN.matcher(clip.token()).matches()) return;
        try { Files.deleteIfExists(previewDir().resolve(clip.token() + ".ogg")); }
        catch (IOException ignored) {
            // A streaming sound can still have the file open on Windows. Clean it at the next start.
        }
    }

    void clearCandidateCache() {
        if (!Files.isDirectory(previewDir(), LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.list(previewDir())) {
            for (Path candidate : paths.toList()) {
                String name = candidate.getFileName().toString();
                if (name.matches("[a-f0-9]{32}\\.ogg") || name.matches("soundpanel-convert-[0-9]+\\.ogg")) {
                    try { Files.deleteIfExists(candidate); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    private Path previewDir() { return configDir.resolve("preview"); }
    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }

    private static Clip prepare(Path path, Path directory) throws IOException {
        String name = path.getFileName().toString();
        if (!AudioConversion.isSupportedName(name)) throw new IOException("Choose a supported audio file.");
        if (!Files.isRegularFile(path) || Files.size(path) > AudioConversion.MAX_INPUT_BYTES) {
            throw new IOException("Choose a local audio file up to 64 MB.");
        }
        Files.createDirectories(directory);
        Clip clip = new Clip(token(), name);
        Path destination = directory.resolve(clip.token() + ".ogg");
        try {
            if (AudioConversion.isOggName(name) && Files.size(path) <= MAX_SIZE) {
                copyBounded(path, destination);
                try { validate(destination); }
                catch (IOException notVorbis) {
                    Files.deleteIfExists(destination);
                    AudioConversion.transcode(path, destination);
                    validate(destination);
                }
            } else {
                AudioConversion.transcode(path, destination);
                validate(destination);
            }
            return clip;
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(destination); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static void copyBounded(Path source, Path destination) throws IOException {
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16384];
            int total = 0;
            for (int n; (n = input.read(buffer)) != -1; ) {
                total += n;
                if (total > MAX_SIZE) throw new IOException("File is larger than 24 MB.");
                output.write(buffer, 0, n);
            }
        }
    }

    private static void validate(Path destination) throws IOException {
        try (var source = Files.newInputStream(destination); var decoder = new OggAudioStream(source)) {
            int channels = decoder.getFormat().getChannels();
            if (channels != 1 && channels != 2) throw new IOException("Use mono or stereo Ogg Vorbis.");
            if (!decoder.read(4096).hasRemaining()) throw new IOException("The OGG file has no audio.");
        } catch (IOException | RuntimeException e) {
            throw new IOException("Invalid Ogg Vorbis audio. Try exporting it again.", e);
        }
    }

    synchronized void clear(Identifier event) throws IOException {
        var updated = new TreeMap<>(mappings);
        if (updated.remove(event.toString()) != null) save(updated);
    }

    private void save(Map<String, Clip> updated) throws IOException {
        Files.createDirectories(configDir);
        Path tmp = Files.createTempFile(configDir, "sounds-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(tmp)) { JSON.toJson(new Settings(updated), writer); }
            Path filename = configDir.resolve("sounds.json");
            try { Files.move(tmp, filename, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, filename, StandardCopyOption.REPLACE_EXISTING); }
            mappings = Map.copyOf(updated);
        } finally { Files.deleteIfExists(tmp); }
    }

    public Path resolveVirtual(Identifier path) {
        if (!path.getNamespace().equals("soundpanel") || !path.getPath().startsWith("sounds/imported/")
                || !path.getPath().endsWith(".ogg")) return null;
        String token = path.getPath().substring("sounds/imported/".length(), path.getPath().length() - ".ogg".length());
        if (!TOKEN.matcher(token).matches()) return null;
        Path file = importDir().resolve(token + ".ogg");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return file;
        Path candidate = previewDir().resolve(token + ".ogg");
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }
}
