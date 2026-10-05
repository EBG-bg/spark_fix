package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free layout; ordinary buttons retain the active resource pack's style. */
class VanillaIntegrationSettingsScreen extends Screen {
    private static final Identifier ADOFAIGO_ICON = Identifier.fromNamespaceAndPath("adofaigo", "icon.png");
    private static final Identifier REI_BRIDGE_ICON = Identifier.fromNamespaceAndPath("rei_recipe_bridge", "icon.png");
    private static final Identifier LITEMATICA_ICON = Identifier.fromNamespaceAndPath("litematica", "icon.png");
    private final Screen parent;
    private final IntegrationSettingsState state = new IntegrationSettingsState();
    private final ItemStack structureFinderIcon = new ItemStack(Items.CRACKED_STONE_BRICKS);
    private final List<Card> cards = new ArrayList<>();
    private final List<TextLine> labels = new ArrayList<>();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private boolean adofaigoExpanded;
    private boolean reiExpanded;
    private boolean litematicaExpanded;
    private boolean structureFinderExpanded;
    private boolean rebuildRequested;
    private double scrollOffset;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private int panelWidth;
    private int left;
    private int titleWidth;
    private int subtitleY;
    private final IntegrationSettingsStyle.HoverFade adofaigoCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade reiCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade litematicaCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade structureFinderCardFade = new IntegrationSettingsStyle.HoverFade();

    VanillaIntegrationSettingsScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.integration_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        cards.clear();
        labels.clear();
        contentWidgets.clear();
        panelWidth = Math.min(580, Math.max(220, width - 32));
        left = (width - panelWidth) / 2;
        boolean wideHeader = panelWidth >= 480;
        titleWidth = panelWidth - (wideHeader ? 270 : 70);
        int titleHeight = font.wordWrapHeight(title, titleWidth);
        subtitleY = 26 + Math.max(24, titleHeight) + 10;
        int sliderY = wideHeader ? 26 : subtitleY;
        addRenderableWidget(new ScrollSensitivitySlider(left + panelWidth - 194, sliderY, 180));
        if (!wideHeader) subtitleY += 34;
        contentTop = subtitleY;
        if (state.pendingRestart()) {
            contentTop += font.wordWrapHeight(Component.translatable(
                    "config.spark_fix.integration_subtitle"), panelWidth - 28) + 12;
        }
        contentBottom = height - 58;
        addRenderableWidget(Button.builder(Component.literal("‹"), ignored -> saveAndClose())
                .bounds(left + 14, 26, 26, 24).build());
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> saveAndClose())
                .bounds(left + panelWidth - 104, height - 48, 90, 24).build());

        int y = contentTop - (int) scrollOffset;
        y = heading("config.spark_fix.integrations_section", y);
        y = module("adofaigo", "config.spark_fix.adofaigo_summary", state.adofaigo(),
                state.adofaigoPendingRestart(), adofaigoExpanded, y, () -> {
                    adofaigoExpanded = !adofaigoExpanded;
                    rebuildRequested = true;
                }, () -> {
                    state.adofaigo(!state.adofaigo());
                    rebuildRequested = true;
                });
        if (state.adofaigo() && adofaigoExpanded) y = details(true, y);
        y = module("REI Recipe Bridge", "config.spark_fix.rei_summary", state.reiRecipeBridge(),
                state.reiRecipeBridgePendingRestart(), reiExpanded, y, () -> {
                    reiExpanded = !reiExpanded;
                    rebuildRequested = true;
                }, () -> {
                    state.reiRecipeBridge(!state.reiRecipeBridge());
                    rebuildRequested = true;
                });
        if (state.reiRecipeBridge() && reiExpanded) y = details(false, y);
        y = module("Litematica", "config.spark_fix.litematica_summary", state.litematica(),
                state.litematicaPendingRestart(), litematicaExpanded, y, () -> {
                    litematicaExpanded = !litematicaExpanded;
                    rebuildRequested = true;
                }, () -> {
                    state.litematica(!state.litematica());
                    rebuildRequested = true;
                });
        if (state.litematica() && litematicaExpanded) y = litematicaDetails(y);
        y = module("Structure Finder", "config.spark_fix.structure_finder_summary", state.structureFinder(),
                state.structureFinderPendingRestart(), structureFinderExpanded, y, () -> {
                    structureFinderExpanded = !structureFinderExpanded;
                    rebuildRequested = true;
                }, () -> {
                    state.structureFinder(!state.structureFinder());
                    rebuildRequested = true;
                });
        if (state.structureFinder() && structureFinderExpanded) y = structureFinderDetails(y);
        if (!state.adofaigo() && !state.reiRecipeBridge() && !state.litematica() && !state.structureFinder()) {
            y += text(Component.translatable("config.spark_fix.disabled_until_enabled"),
                    left + 24, y, panelWidth - 48, IntegrationSettingsStyle.MUTED) + 8;
        }
        contentHeight = y - contentTop + (int) scrollOffset;
        double previousOffset = scrollOffset;
        clampScroll();
        if (previousOffset != scrollOffset) {
            init();
            return;
        }
        for (AbstractWidget widget : contentWidgets) {
            widget.visible = widget.getY() >= contentTop && widget.getBottom() <= contentBottom;
        }
    }

    private int heading(String key, int y) {
        return y + text(Component.translatable(key), left + 18, y, panelWidth - 36, 0xFFCAE2FF) + 10;
    }

    private int module(String name, String summary, boolean selected, boolean pending,
                       boolean expanded, int y, Runnable fold, Runnable toggle) {
        boolean compact = panelWidth < 380;
        int iconWidth = name.equals("Litematica") && !state.litematicaInstalled() ? 0 : 32;
        int textLeft = left + 24 + iconWidth + 6;
        int textWidth = panelWidth - (compact ? 56 : 212) - (iconWidth == 0 ? 0 : 38);
        int nameHeight = text(Component.literal(name.equals("adofaigo") ? "adofai" : name), textLeft, y + 10, textWidth, 0xFFFFFFFF);
        int summaryHeight = text(Component.translatable(summary), textLeft, y + 15 + nameHeight,
                textWidth, IntegrationSettingsStyle.MUTED);
        int bodyHeight = nameHeight + summaryHeight + 5;
        int toggleY = compact ? y + 18 + bodyHeight : y + 10;
        int toggleX = compact ? left + 24 : left + panelWidth - 168;
        Button enable = new ToggleButton(toggleX, toggleY, 114, 24,
                Component.translatable("config.spark_fix.enable_mod"), selected, true, toggle);
        contentWidgets.add(enable);
        addRenderableWidget(enable);
        if (selected) {
            Button disclosure = new DisclosureButton(toggleX + 120, toggleY,
                    Component.translatable(name.equals("adofaigo") ? "config.spark_fix.adofaigo_section"
                            : name.equals("Litematica") ? "config.spark_fix.litematica_section"
                            : name.equals("Structure Finder") ? "config.spark_fix.structure_finder_section"
                            : "config.spark_fix.rei_bridge_section"),
                    expanded, fold);
            contentWidgets.add(disclosure);
            addRenderableWidget(disclosure);
        }
        int h = (compact ? bodyHeight + 32 : Math.max(24, bodyHeight)) + 20;
        if (pending) {
            h += text(Component.translatable("config.spark_fix.restart_required"), left + 24,
                    y + h - 2, panelWidth - 48, 0xFFFFD782) + 6;
        }
        cards.add(new Card(y, h, IntegrationSettingsStyle.MODULE_CARD, selected ? fold : null, enable,
                name.equals("adofaigo") ? adofaigoCardFade : name.equals("Litematica") ? litematicaCardFade
                        : name.equals("Structure Finder") ? structureFinderCardFade : reiCardFade,
                name.equals("adofaigo") ? ADOFAIGO_ICON : name.equals("Litematica")
                        ? state.litematicaInstalled() ? LITEMATICA_ICON : null
                        : name.equals("Structure Finder") ? null : REI_BRIDGE_ICON,
                name.equals("adofaigo") || name.equals("Litematica") ? 32 : 128,
                name.equals("Structure Finder")));
        return y + h + 9;
    }

    private int details(boolean adofaigo, int y) {
        int h = 10;
        if (adofaigo || !state.reiInstalled()) {
            h += text(Component.translatable(adofaigo ? "config.spark_fix.adofaigo_description"
                            : "config.spark_fix.rei_missing"), left + 24, y + h,
                    panelWidth - 48, IntegrationSettingsStyle.MUTED) + 10;
        } else {
            h += addReiSettings(y + h);
        }
        cards.add(new Card(y, h, IntegrationSettingsStyle.SETTINGS_PANEL, null, null, null, null, 0, false));
        return y + h + 9;
    }

    private int litematicaDetails(int y) {
        int h = 10;
        if (!state.litematicaInstalled()) {
            h += text(Component.translatable("config.spark_fix.litematica_missing"), left + 24, y + h,
                    panelWidth - 48, IntegrationSettingsStyle.MUTED) + 10;
        } else if (!SparkFixConfig.litematicaEnabledAtStartup()) {
            h += text(Component.translatable("config.spark_fix.restart_required"), left + 24, y + h,
                    panelWidth - 48, 0xFFFFFF55) + 10;
        } else {
            AbstractWidget open = projectionButton(left + 24, y + h, panelWidth - 48,
                    () -> minecraft.setScreenAndShow(IntegrationSettingsRouter.createLitematica(this)));
            h += open.getHeight() + 10;
        }
        cards.add(new Card(y, h, IntegrationSettingsStyle.SETTINGS_PANEL, null, null, null, null, 0, false));
        return y + h + 9;
    }

    private int structureFinderDetails(int y) {
        int h = 10;
        if (!SparkFixConfig.structureFinderEnabledAtStartup()) {
            h += text(Component.translatable("config.spark_fix.restart_required"), left + 24, y + h,
                    panelWidth - 48, 0xFFFFFF55) + 10;
        } else {
            Button open = button(Component.translatable("gui.spark_fix.structure_finder.open"),
                    left + 24, y + h, panelWidth - 48,
                    () -> minecraft.setScreenAndShow(new StructureFinderScreen(this)));
            h += open.getHeight() + 10;
        }
        cards.add(new Card(y, h, IntegrationSettingsStyle.SETTINGS_PANEL, null, null, null, null, 0, false));
        return y + h + 9;
    }

    private int text(Component message, int x, int y, int width, int color) {
        labels.add(new TextLine(message, x, y, width, color));
        return font.wordWrapHeight(message, width);
    }

    private Button button(Component message, int x, int y, int width, Runnable action) {
        Button button = Button.builder(message, ignored -> action.run()).bounds(x, y, width, 24).build();
        contentWidgets.add(button);
        addRenderableWidget(button);
        return button;
    }

    private AbstractWidget projectionButton(int x, int y, int width, Runnable action) {
        AbstractWidget button = new ProjectionButton(x, y, width, action);
        contentWidgets.add(button);
        addRenderableWidget(button);
        return button;
    }

    @Override
    public void tick() {
        super.tick();
        if (!rebuildRequested) return;
        rebuildRequested = false;
        init();
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(Math.max(0, contentHeight - (contentBottom - contentTop)), scrollOffset));
    }

    private int addReiSettings(int y) {
        ReiInlineSettings settings = state.reiSettings();
        if (settings == null) {
            return text(Component.translatable("config.spark_fix.rei_settings_failed"),
                    left + 24, y, panelWidth - 48, 0xFFFFD782) + 10;
        }
        int start = y;
        y += reiToggle("rei_recipe_bridge.vanilla_toggle", settings.provideVanilla(), y, () -> {
            settings.toggleVanilla();
            rebuildRequested = true;
        });
        y += reiToggle("rei_recipe_bridge.capture_toggle", settings.captureUnlocked(), y, () -> {
            settings.toggleCapture();
            rebuildRequested = true;
        });
        for (ReiInlineSettings.Line line : settings.information()) {
            y += text(line.text(), left + 24, y, panelWidth - 48, line.color()) + 8;
        }
        Button refresh = button(Component.translatable("rei_recipe_bridge.refresh"), left + 24, y, panelWidth - 48, () -> {
            settings.refresh();
            rebuildRequested = true;
        });
        refresh.active = settings.canRefresh();
        refresh.setTooltip(Tooltip.create(Component.translatable(settings.canRefresh()
                ? "config.spark_fix.rei_refresh_help" : "config.spark_fix.rei_refresh_no_world")));
        return y + 34 - start;
    }

    private int reiToggle(String key, boolean selected, int y, Runnable action) {
        Component message = Component.translatable(key);
        int height = Math.max(28, font.wordWrapHeight(message, panelWidth - 96) + 12);
        Button toggle = new ToggleButton(left + 24, y, panelWidth - 48, height, message, selected, action);
        contentWidgets.add(toggle);
        addRenderableWidget(toggle);
        return height + 8;
    }

    private void saveAndClose() {
        state.save();
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        // Controls consume their own clicks before the rest of the module card can fold.
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || click.y() < contentTop || click.y() >= contentBottom
                || click.x() < left + 14 || click.x() >= left + panelWidth - 14) return false;
        // Partially clipped controls are hidden; their bounds must not become folding targets.
        for (AbstractWidget widget : contentWidgets) {
            if (click.x() >= widget.getX() && click.x() < widget.getRight()
                    && click.y() >= widget.getY() && click.y() < widget.getBottom()) return false;
        }
        for (Card card : cards) {
            if (card.fold() != null && click.y() >= card.y() && click.y() < card.y() + card.height()) {
                card.fold().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY < contentTop || mouseY > contentBottom || mouseX < left || mouseX > left + panelWidth) return false;
        scrollOffset -= verticalAmount * 18 * SparkFixConfig.scrollSensitivity();
        clampScroll();
        rebuildRequested = true;
        return true;
    }

    @Override
    public void onClose() { saveAndClose(); }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        IntegrationSettingsStyle.roundedRect(graphics, left, 16, panelWidth, height - 32, 12,
                IntegrationSettingsStyle.PANEL);
        graphics.textWithWordWrap(font, title, left + 50, 32, titleWidth, 0xFFFFFFFF);
        if (state.pendingRestart()) {
            graphics.textWithWordWrap(font, Component.translatable("config.spark_fix.integration_subtitle"),
                    left + 14, subtitleY,
                    panelWidth - 28, 0xFFFFFF55);
        }
        graphics.enableScissor(left + 14, contentTop, left + panelWidth - 14, contentBottom);
        for (Card card : cards) {
            boolean overEnable = card.enable() != null && mouseX >= card.enable().getX()
                    && mouseX < card.enable().getRight() && mouseY >= card.enable().getY()
                    && mouseY < card.enable().getBottom();
            boolean hovered = card.fold() != null && !overEnable
                    && mouseX >= left + 14 && mouseX < left + panelWidth - 14
                    && mouseY >= contentTop && mouseY < contentBottom
                    && mouseY >= card.y() && mouseY < card.y() + card.height();
            IntegrationSettingsStyle.roundedRect(graphics, left + 14, card.y(), panelWidth - 28,
                    card.height(), 8, card.fade() == null ? card.color() : IntegrationSettingsStyle.blend(
                            card.color(), IntegrationSettingsStyle.MODULE_CARD_HOVER, card.fade().sample(hovered)));
        }
        for (TextLine label : labels) {
            graphics.textWithWordWrap(font, label.message(), label.x(), label.y(), label.width(), label.color());
        }
        for (Card card : cards) {
            if (card.icon() != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, card.icon(), left + 24, card.y() + 10,
                        0.0F, 0.0F, 32, 32, card.iconSize(), card.iconSize());
            }
            if (card.structureFinder()) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(left + 24, card.y() + 10);
                graphics.pose().scale(2, 2);
                graphics.item(structureFinderIcon, 0, 0);
                graphics.pose().popMatrix();
            }
        }
        graphics.disableScissor();
    }

    private static final class ToggleButton extends Button {
        private final boolean selected;
        private final boolean outlined;
        private final IntegrationSettingsStyle.HoverFade hoverFade = new IntegrationSettingsStyle.HoverFade();

        private ToggleButton(int x, int y, int width, int height, Component message, boolean selected, Runnable action) {
            this(x, y, width, height, message, selected, false, action);
        }

        private ToggleButton(int x, int y, int width, int height, Component message, boolean selected,
                             boolean outlined, Runnable action) {
            super(x, y, width, height, message, ignored -> action.run(), Button.DEFAULT_NARRATION);
            this.selected = selected;
            this.outlined = outlined;
            setTooltip(Tooltip.create(message.copy().append(": ").append(Component.translatable(
                    selected ? "config.spark_fix.on" : "config.spark_fix.off"))));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            float hoverAmount = hoverFade.sample(isHoveredOrFocused());
            IntegrationSettingsStyle.toggle(graphics, Minecraft.getInstance().font, getMessage(),
                    getX(), getY(), getWidth(), getHeight(), selected, hoverAmount);
            if (outlined) IntegrationSettingsStyle.moduleToggleOutline(graphics,
                    getX(), getY(), getWidth(), getHeight(), hoverAmount);
        }
    }

    private static final class DisclosureButton extends Button {
        private final boolean expanded;

        private DisclosureButton(int x, int y, Component message, boolean expanded, Runnable fold) {
            super(x, y, 24, 24, message, ignored -> fold.run(), Button.DEFAULT_NARRATION);
            this.expanded = expanded;
            setTooltip(Tooltip.create(message));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            IntegrationSettingsStyle.disclosure(graphics, getX(), getY(), getWidth(), getHeight(),
                    expanded, isHoveredOrFocused());
        }
    }

    private record Card(int y, int height, int color, Runnable fold, Button enable,
                        IntegrationSettingsStyle.HoverFade fade, Identifier icon, int iconSize, boolean structureFinder) {}
    private record TextLine(Component message, int x, int y, int width, int color) {}
}
