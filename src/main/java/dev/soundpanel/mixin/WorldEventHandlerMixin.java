package dev.soundpanel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.soundpanel.BottleImpact;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WorldEventHandler.class)
abstract class WorldEventHandlerMixin {
    // ExperienceBottleEntity sends the ordinary splash world event with the vanilla water-potion color.
    private static final int BOTTLE_WATER_COLOR = 0xff385dc6;
    @Shadow @Final private ClientWorld world;

    @WrapMethod(method = "processWorldEvent")
    private void soundpanel$distinguishExperienceBottle(int event, BlockPos pos, int data, Operation<Void> original) {
        if (event != WorldEvents.SPLASH_POTION_SPLASHED || data != BOTTLE_WATER_COLOR || !BottleImpact.hasAssignment()) {
            original.call(event, pos, data);
            return;
        }

        Vec3d center = Vec3d.ofCenter(pos);
        Box area = new Box(pos).expand(1);
        double bottle = nearest(ExperienceBottleEntity.class, area, pos, center);
        double potion = nearest(PotionEntity.class, area, pos, center);
        BottleImpact.run(bottle < potion, () -> original.call(event, pos, data));
    }

    @org.spongepowered.asm.mixin.Unique
    private <T extends Entity> double nearest(Class<T> kind, Box area, BlockPos pos, Vec3d center) {
        double best = Double.POSITIVE_INFINITY;
        for (T candidate : world.getEntitiesByClass(kind, area, entity -> !entity.isRemoved())) {
            double distance = candidate.squaredDistanceTo(center);
            if (!candidate.getBlockPos().equals(pos)) distance += 4;
            best = Math.min(best, distance);
        }
        return best;
    }
}
