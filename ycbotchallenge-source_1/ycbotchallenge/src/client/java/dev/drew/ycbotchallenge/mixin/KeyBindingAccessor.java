package dev.drew.ycbotchallenge.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets us increment {@code timesPressed} so vanilla's own
 * {@code MinecraftClient.handleInputEvents} -> {@code doAttack()} fires on the
 * next tick. {@code KeyBinding.setPressed(true)} only sets the held flag and
 * does NOT produce a {@code wasPressed()} edge, so a synthetic press via that
 * method never attacks. Incrementing the counter is the faithful "real
 * key-press" path: vanilla owns the ray-trace, swing, and cooldown.
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int ycbotchallenge$getTimesPressed();

    @Accessor("timesPressed")
    void ycbotchallenge$setTimesPressed(int value);
}
