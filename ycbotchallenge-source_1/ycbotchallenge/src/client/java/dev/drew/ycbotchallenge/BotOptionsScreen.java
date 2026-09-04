package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * 0.9.30: the in-game options screen (Y). One ON/OFF button per feature flag, laid out
 * in columns, saved to config/ycbotchallenge.json the moment a button is clicked and
 * applied live (every controller reads its flag each tick). Vanilla widgets only — no
 * ModMenu/Cloth dependency, so the CI build is unchanged. While it is open the bot
 * releases its keys and does nothing else; the hotkeys are ignored on this screen.
 */
public class BotOptionsScreen extends Screen {
    /** One toggle: the config field name (logged), its label, and the getter/setter on the live config. */
    public record Option(String key, String label, BooleanSupplier get, Consumer<Boolean> set) {}

    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int ROW_H = 22;
    private static final int GAP_X = 8;
    private static final int TOP = 36;

    private final List<Option> options;
    private final BiConsumer<String, Boolean> onChange;

    public BotOptionsScreen(List<Option> options, BiConsumer<String, Boolean> onChange) {
        super(Text.literal("YCBotChallenge options"));
        this.options = options;
        this.onChange = onChange;
    }

    @Override
    protected void init() {
        int cols = Math.max(1, Math.min(3, (this.width - GAP_X) / (BUTTON_W + GAP_X)));
        int rows = (options.size() + cols - 1) / cols;
        int gridW = cols * BUTTON_W + (cols - 1) * GAP_X;
        int left = (this.width - gridW) / 2;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            int col = i % cols;
            int row = i / cols;
            int x = left + col * (BUTTON_W + GAP_X);
            int y = TOP + row * ROW_H;
            addDrawableChild(CyclingButtonWidget.onOffBuilder(o.get().getAsBoolean())
                .build(x, y, BUTTON_W, BUTTON_H, Text.literal(o.label()), (button, value) -> {
                    o.set().accept(value);
                    if (onChange != null) onChange.accept(o.key(), value);
                }));
        }
        int doneY = Math.min(this.height - BUTTON_H - 8, TOP + rows * ROW_H + 8);
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
            .dimensions((this.width - BUTTON_W) / 2, doneY, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("saved to config/ycbotchallenge.json on click · applies at once"),
            this.width / 2, 24, 0xFF9A9A9A);
    }

    @Override
    public boolean shouldPause() { return false; }
}
