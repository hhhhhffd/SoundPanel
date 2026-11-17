package dev.soundpanel.mixin;

import dev.soundpanel.SoundPanel;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSoundInstance.class)
abstract class AbstractSoundInstanceMixin {
    @Shadow protected Sound sound;
    @Inject(method = "getSoundSet", at = @At("RETURN"), cancellable = true)
    private void soundpanel$provideMissingSound(SoundManager manager, CallbackInfoReturnable<WeightedSoundSet> cir) {
        if (cir.getReturnValue() != null && sound != null && sound != SoundManager.MISSING_SOUND) return;
        SoundInstance event = (SoundInstance) (Object) this;
        Sound custom = SoundPanel.missingReplacement(event);
        if (custom == null) return;
        var replacement = new WeightedSoundSet(event.getId(), null);
        replacement.add(custom);
        sound = custom;
        cir.setReturnValue(replacement);
    }
}
