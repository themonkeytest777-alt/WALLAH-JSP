package com.cobbleautominer.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
    @Accessor("blockBreakingCooldown")
    int cam$getCooldown();

    @Accessor("blockBreakingCooldown")
    void cam$setCooldown(int value);
}
