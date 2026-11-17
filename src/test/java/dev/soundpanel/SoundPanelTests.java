package dev.soundpanel;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SoundPanelTests {
    public static void main(String[] args) throws Exception {
        PackSoundCatalogTests.run();
        var zombie = Identifier.ofVanilla("entity.zombie.hurt");
        var player = Identifier.ofVanilla("entity.player.attack.crit");
        var groups = SoundCatalog.group(List.of(zombie, player,
                Identifier.ofVanilla("block.glass.break"), SoundCatalog.BOTTLE_THROW));
        var bottle = groups.stream().filter(g -> g.id().equals("minecraft:experience_bottle")).findFirst().orElseThrow();
        check(bottle.events().stream().map(SoundCatalog.Event::label).toList().equals(List.of("Throw", "Shatter", "XP pickup")), "Bottle's separate actions");
        check(groups.stream().filter(g -> g.id().equals("minecraft:player")).findFirst().orElseThrow().events().getFirst().id().equals(player), "player action grouping");
        check(BottleImpact.replacementId(SoundCatalog.SPLASH_BREAK) == null, "ordinary splash unchanged");
        BottleImpact.run(true, () -> {
            check(BottleImpact.replacementId(SoundCatalog.SPLASH_BREAK).equals(SoundCatalog.BOTTLE_SHATTER), "bottle shatter kept separate");
            check(BottleImpact.replacementId(player) == null, "bottle context does not touch other sounds");
            BottleImpact.run(false, () -> check(BottleImpact.replacementId(SoundCatalog.SPLASH_BREAK) == null, "nested ordinary splash"));
            check(BottleImpact.replacementId(SoundCatalog.SPLASH_BREAK).equals(SoundCatalog.BOTTLE_SHATTER), "previous context restored");
        });
        check(BottleImpact.replacementId(SoundCatalog.SPLASH_BREAK) == null, "world-event context does not leak");

        Path root = Files.createTempDirectory("sound-panel-tests");
        Path sounds = Files.createDirectory(root.resolve("sounds"));
        Path clip = sounds.resolve("valid.ogg");
        try (var in = SoundPanelTests.class.getResourceAsStream("/clip.ogg")) {
            Files.write(clip, in.readAllBytes());
        }
        var configDir = root.resolve("config");
        var config = new SoundConfig(configDir, sounds);
        var defaultClip = config.install(player, clip);
        var shatterClip = config.install(SoundCatalog.BOTTLE_SHATTER, clip);
        check(!defaultClip.token().equals(shatterClip.token()), "independent mappings");
        check(config.clipFor(player).equals(defaultClip), "individual action mapping");
        var virtualPath = Identifier.of("soundpanel", "sounds/imported/" + defaultClip.token() + ".ogg");
        check(Files.mismatch(clip, config.resolveVirtual(virtualPath)) == -1, "native OGG files copied byte-for-byte");
        check(config.resolveVirtual(Identifier.of("soundpanel", "sounds/imported/../config.ogg")) == null, "path traversal rejected");

        var reloaded = new SoundConfig(configDir, sounds);
        reloaded.load();
        check(reloaded.clipFor(player).equals(defaultClip), "clip persistence");
        reloaded.clear(SoundCatalog.BOTTLE_SHATTER);
        check(reloaded.clipFor(SoundCatalog.BOTTLE_SHATTER) == null, "bottle reset falls back to original");
        check(reloaded.clipFor(player).equals(defaultClip), "other sound survives reset");
        var malformed = sounds.resolve("malformed.ogg");
        Files.writeString(malformed, "not vorbis");
        bad(() -> reloaded.install(player, malformed));
        bad(() -> reloaded.install(player, sounds.resolve("file.exe")));
        var overflow = sounds.resolve("overflow.ogg");
        try (var output = new java.io.RandomAccessFile(overflow.toFile(), "rw")) {
            output.setLength(AudioConversion.MAX_INPUT_BYTES + 1);
        }
        bad(() -> reloaded.install(player, overflow));
        check(reloaded.clipFor(player).equals(defaultClip), "failed imports preserve previous replacement");
        Files.delete(reloaded.resolveVirtual(virtualPath));
        check(reloaded.clipFor(player) == null, "lost clip falls back to original sound");
        check(AudioConversion.isSupportedName("voice.MP3"), "uppercase MP3 detected");
        check(AudioConversion.isSupportedName("sample.flac"), "FLAC detected");
        check(!AudioConversion.isSupportedName("playlist.m3u8"), "network/playlist formats are not supported");
        System.out.println("PASS: entity events, Bottle impact context, independent assignments, real Ogg Vorbis import, validation, reset and persistence");
    }

    private static void check(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }
    private static void bad(IOAction action) throws IOException {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException expected) { }
    }
    private interface IOAction { void run() throws IOException; }
}
