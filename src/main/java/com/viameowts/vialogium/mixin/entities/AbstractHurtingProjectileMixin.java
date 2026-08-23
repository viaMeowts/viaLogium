package com.viameowts.vialogium.mixin.entities;

import com.viameowts.vialogium.utility.PlayerCausable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractHurtingProjectile.class)
public abstract class AbstractHurtingProjectileMixin implements PlayerCausable {
    @Nullable
    @Override
    public Player getCausingPlayer() {
        if (((AbstractHurtingProjectile) (Object) this).getOwner() instanceof Mob entity) {
            if (entity.getTarget() instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
