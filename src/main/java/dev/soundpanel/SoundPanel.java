package dev.soundpanel;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SoundPanel implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("Sound Panel");
    public static final SoundConfig CONFIG = new SoundConfig();
    private static volatile SoundInstance preview;

    @Override public void onInitializeClient() {
        CONFIG.load();
        CONFIG.clearCandidateCache();
        PackSoundCatalog.register();
        try { java.nio.file.Files.createDirectories(CONFIG.sourceDir()); }
        catch (java.io.IOException e) { LOG.warn("Unable to create sounds folder", e); }
    }

    public static Sound replace(Sound original, SoundInstance instance) {
        if (instance == preview || original == SoundManager.MISSING_SOUND || original == SoundManager.INTENTIONALLY_EMPTY_SOUND) return original;
        var replacementId = BottleImpact.replacementId(instance.getId());
        SoundConfig.Clip clip = replacementId != null ? CONFIG.clipFor(replacementId) : null;
        if (clip == null && PackSoundCatalog.isActive(instance.getId())) {
            clip = CONFIG.clipFor(serverKey(MinecraftClient.getInstance(), instance.getId()));
        }
        if (clip == null) clip = CONFIG.clipFor(instance.getId());
        return clip == null ? original : custom(clip, original == null ? 16 : original.getAttenuation());
    }

    public static Sound missingReplacement(SoundInstance instance) { return replace(null, instance); }

    private static Sound custom(SoundConfig.Clip clip, int attenuation) {
        return new Sound(Identifier.of("soundpanel", "imported/" + clip.token()), random -> 1f, random -> 1f,
                1, Sound.RegistrationType.FILE, true, false, attenuation);
    }

    static Identifier serverKey(MinecraftClient client, Identifier id) {
        var server = client == null ? null : client.getCurrentServerEntry();
        String address = server == null ? "" : server.address.strip().toLowerCase(java.util.Locale.ROOT);
        String fingerprint = java.util.UUID.nameUUIDFromBytes(address.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return Identifier.of("soundpanel", "server/" + fingerprint + "/" + id.getNamespace() + "/" + id.getPath());
    }

    static boolean preview(MinecraftClient client, Identifier id, Identifier assignment, boolean original) {
        stopPreview(client);
        SoundConfig.Clip clip = original ? null : CONFIG.clipFor(assignment);
        if (clip != null) {
            preview = new ClipPreview(id, custom(clip, 16));
        } else {
            if (SoundCatalog.BOTTLE_SHATTER.equals(id)) id = SoundCatalog.SPLASH_BREAK;
            var factory = client.getSoundManager().get(id);
            if (factory == null || factory.getWeight() == 0) return false;
            preview = PositionedSoundInstance.ui(SoundEvent.of(id), 1f, 1f);
        }
        return client.getSoundManager().play(preview) == SoundSystem.PlayResult.STARTED;
    }

    static boolean previewCandidate(MinecraftClient client, Identifier event, SoundConfig.Clip clip) {
        stopPreview(client);
        Sound sound = custom(clip, 16);
        if (CONFIG.resolveVirtual(sound.getLocation()) == null) return false;
        preview = new ClipPreview(event, sound);
        return client.getSoundManager().play(preview) == SoundSystem.PlayResult.STARTED;
    }

    static void stopPreview(MinecraftClient client) {
        if (preview != null) client.getSoundManager().stop(preview);
        preview = null;
    }

    private static final class ClipPreview extends AbstractSoundInstance {
        private final Sound clip;
        ClipPreview(Identifier id, Sound clip) {
            super(id, SoundCategory.MASTER, Random.create());
            this.clip = clip;
            this.relative = true;
            this.attenuationType = AttenuationType.NONE;
            this.volume = this.pitch = 1f;
        }
        @Override public WeightedSoundSet getSoundSet(SoundManager manager) {
            WeightedSoundSet set = new WeightedSoundSet(id, null);
            set.add(clip);
            sound = clip;
            return set;
        }
    }
}
