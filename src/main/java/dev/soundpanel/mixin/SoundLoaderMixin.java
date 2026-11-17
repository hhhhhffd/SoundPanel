package dev.soundpanel.mixin;

import dev.soundpanel.ClipResourceFactory;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.resource.ResourceFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundLoader.class)
abstract class SoundLoaderMixin {
    @Shadow @Final @Mutable private ResourceFactory resourceFactory;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void soundpanel$provideClips(ResourceFactory original, CallbackInfo ci) {
        this.resourceFactory = new ClipResourceFactory(original);
    }
}
