package dev.soundpanel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.soundpanel.SoundPanel;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SoundSystem.class)
abstract class SoundSystemMixin {
    @ModifyExpressionValue(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundInstance;getSound()Lnet/minecraft/client/sound/Sound;"))
    private Sound soundpanel$choose(Sound original, SoundInstance instance) {
        return SoundPanel.replace(original, instance);
    }
}
