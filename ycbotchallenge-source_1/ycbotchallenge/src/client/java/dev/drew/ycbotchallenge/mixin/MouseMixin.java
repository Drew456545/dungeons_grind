package dev.drew.ycbotchallenge.mixin;

import dev.drew.ycbotchallenge.MouseDriver;
import dev.drew.ycbotchallenge.YCBotChallengeClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds planned cursor deltas before vanilla consumes them. Vanilla sensitivity
 * / GCD / invert-Y stay the only rotation math — we never write yaw/pitch.
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse(D)V", at = @At("HEAD"))
    private void ycbotchallenge$injectCursor(double timeDelta, CallbackInfo ci) {
        if (!YCBotChallengeClient.enabled) return;
        double[] add = MouseDriver.INSTANCE.pollCursorDelta(timeDelta);
        if (add == null) return;
        this.cursorDeltaX += add[0];
        this.cursorDeltaY += add[1];
    }
}
