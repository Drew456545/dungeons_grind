package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    /**
     * One toggle: the config field name (logged), its label, the getter/setter on the live
     * config, and (0.9.33) an optional live status drawn under the button — what the module
     * is doing right now ("idle · last visit 12m ago", "suspended", the plan line).
     */
    public record Option(String key, String label, BooleanSupplier get, Consumer<Boolean> set, Supplier<String> status,
                         List<String> choices, Supplier<String> getChoice, Consumer<String> setChoice) {
        public Option(String key, String label, BooleanSupplier get, Consumer<Boolean> set) {
            this(key, label, get, set, null, null, null, null);
        }
        public Option(String key, String label, BooleanSupplier get, Consumer<Boolean> set, Supplier<String> status) {
            this(key, label, get, set, status, null, null, null);
        }
        /** 0.9.41: a button that cycles through {@code choices} (the auto-disconnect timer) instead of ON/OFF. */
        public static Option choice(String key, String label, List<String> choices, Supplier<String> get, Consumer<String> set, Supplier<String> status) {
            return new Option(key, label, null, null, status, choices, get, set);
        }
    }

    private record StatusSlot(int x, int y, int w, Supplier<String> status) {}

    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int ROW_H = 22;
    private static final int ROW_H_STATUS = 32;
    private static final int GAP_X = 8;
    private static final int TOP = 36;

    private final List<Option> options;
    private final BiConsumer<String, Object> onChange;
    private final List<StatusSlot> statusSlots = new ArrayList<>();

    public BotOptionsScreen(List<Option> options, BiConsumer<String, Object> onChange) {
        super(Text.literal("YCBotChallenge options"));
        this.options = options;
        this.onChange = onChange;
    }

    @Override
    protected void init() {
        statusSlots.clear();
        boolean anyStatus = options.stream().anyMatch(o -> o.status() != null);
        int cols = Math.max(1, Math.min(3, (this.width - GAP_X) / (BUTTON_W + GAP_X)));
        int rows = (options.size() + cols - 1) / cols;
        // Status lines need taller rows; drop them when the screen cannot fit the grid.
        int rowH = anyStatus && TOP + rows * ROW_H_STATUS + BUTTON_H + 16 <= this.height ? ROW_H_STATUS : ROW_H;
        boolean showStatus = rowH == ROW_H_STATUS;
        int gridW = cols * BUTTON_W + (cols - 1) * GAP_X;
        int left = (this.width - gridW) / 2;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            int col = i % cols;
            int row = i / cols;
            int x = left + col * (BUTTON_W + GAP_X);
            int y = TOP + row * rowH;
            if (o.choices() != null) {
                String cur = o.getChoice().get();
                if (cur == null || !o.choices().contains(cur)) cur = o.choices().get(0);
                addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(v), cur).values(o.choices())
                    .build(x, y, BUTTON_W, BUTTON_H, Text.literal(o.label()), (button, value) -> {
                        o.setChoice().accept(value);
                        if (onChange != null) onChange.accept(o.key(), value);
                    }));
            } else {
                addDrawableChild(CyclingButtonWidget.onOffBuilder(o.get().getAsBoolean())
                    .build(x, y, BUTTON_W, BUTTON_H, Text.literal(o.label()), (button, value) -> {
                        o.set().accept(value);
                        if (onChange != null) onChange.accept(o.key(), value);
                    }));
            }
            if (showStatus && o.status() != null) statusSlots.add(new StatusSlot(x + 2, y + BUTTON_H + 1, BUTTON_W - 4, o.status()));
        }
        int doneY = Math.min(this.height - BUTTON_H - 8, TOP + rows * rowH + 8);
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
        for (StatusSlot s : statusSlots) {
            String text;
            try {
                text = s.status().get();
            } catch (RuntimeException e) {
                text = null;
            }
            if (text == null || text.isBlank()) continue;
            String shown = text;
            while (shown.length() > 4 && this.textRenderer.getWidth(shown) > s.w()) shown = shown.substring(0, shown.length() - 1);
            context.drawTextWithShadow(this.textRenderer, shown, s.x(), s.y(), 0xFF9A9A9A);
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
