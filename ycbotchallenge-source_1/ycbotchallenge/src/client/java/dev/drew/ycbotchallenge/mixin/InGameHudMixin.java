package dev.drew.ycbotchallenge.mixin;

import dev.drew.ycbotchallenge.YCBotChallengeClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.9.38: titles and subtitles. The zone boss talks through the title overlay ("Hit the
 * targets to kill the boss and recieve the rewards!" / "Targets Hit - 10") and nothing
 * else in the mod ever saw them - the chat event carries chat and the action bar only, and
 * "Targets Hit" appears in none of 37 logs. Both setters are the stable public API the
 * network handler calls; the strings are handed over as volatile fields and read on the
 * client tick, the same handoff the captcha chat line uses.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void ycbotchallenge$onTitle(Text title, CallbackInfo ci) {
        YCBotChallengeClient.onTitle(title != null ? title.getString() : null);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void ycbotchallenge$onSubtitle(Text subtitle, CallbackInfo ci) {
        YCBotChallengeClient.onSubtitle(subtitle != null ? subtitle.getString() : null);
    }
}
