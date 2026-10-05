package dev.codex.spark_fix;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.component.VanillaWidgetComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Optional owo layout with ordinary screen backgrounds and resource-pack button sprites. */
final class OwoIntegrationSettingsScreen extends BaseOwoScreen<FlowLayout> {
    private static final Identifier ADOFAIGO_ICON = Identifier.fromNamespaceAndPath("adofaigo", "icon.png");
    private static final Identifier REI_BRIDGE_ICON = Identifier.fromNamespaceAndPath("rei_recipe_bridge", "icon.png");
    private static final Identifier LITEMATICA_ICON = Identifier.fromNamespaceAndPath("litematica", "icon.png");
    private final Screen parent;
    private final IntegrationSettingsState state = new IntegrationSettingsState();
    private boolean adofaigoExpanded;
    private boolean reiExpanded;
    private boolean litematicaExpanded;
    private boolean structureFinderExpanded;
    private boolean rebuildQueued;
    private int panelWidth;
    private SettingsScroll scroll;
    private int pointerX;
    private int pointerY;
    private final IntegrationSettingsStyle.HoverFade adofaigoCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade reiCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade litematicaCardFade = new IntegrationSettingsStyle.HoverFade();
    private final IntegrationSettingsStyle.HoverFade structureFinderCardFade = new IntegrationSettingsStyle.HoverFade();

    OwoIntegrationSettingsScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.integration_title"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        pointerX = mouseX;
        pointerY = mouseY;
        // BaseOwoScreen suppresses Screen's background. The utility Screen keeps
        // the vanilla panorama, resource-pack textures and configured blur.
        OwoUIGraphics.utilityScreen().extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void init() {
        boolean existing = this.uiAdapter != null;
        super.init();
        if (existing && !this.invalid) rebuild();
    }

    @Override
    protected void build(FlowLayout root) {
        panelWidth = Math.max(220, Math.min(580, this.width - 32));
        root.surface(Surface.BLANK);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);
        FlowLayout panel = UIContainers.verticalFlow(Sizing.fixed(panelWidth),
                Sizing.fixed(Math.max(160, this.height - 32)));
        panel.padding(Insets.of(14));
        panel.gap(10);
        panel.surface((graphics, component) -> IntegrationSettingsStyle.roundedRect(graphics,
                component.x(), component.y(), component.width(), component.height(), 12,
                IntegrationSettingsStyle.PANEL));

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content());
        header.gap(10);
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.child(action(Component.literal("‹"), this::saveAndClose, 26, 24));
        LabelComponent titleLabel = label(this.title, 0xFFFFFFFF);
        boolean wideHeader = panelWidth >= 480;
        titleLabel.maxWidth(panelWidth - (wideHeader ? 270 : 70));
        FlowLayout titleArea = UIContainers.horizontalFlow(Sizing.expand(), Sizing.content());
        titleArea.child(titleLabel);
        header.child(titleArea);
        var sensitivity = new FocusableWidgetComponent(new ScrollSensitivitySlider(0, 0, 180));
        sensitivity.sizing(Sizing.fixed(180), Sizing.fixed(24));
        if (wideHeader) header.child(sensitivity);
        panel.child(header);
        if (!wideHeader) {
            FlowLayout sliderRow = UIContainers.horizontalFlow(Sizing.fill(), Sizing.fixed(24));
            sliderRow.horizontalAlignment(HorizontalAlignment.RIGHT);
            sliderRow.child(sensitivity);
            panel.child(sliderRow);
        }
        if (state.pendingRestart()) {
            panel.child(label(Component.translatable("config.spark_fix.integration_subtitle"),
                    0xFFFFFF55));
        }

        FlowLayout content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content());
        content.gap(9);
        content.child(section("config.spark_fix.integrations_section"));
        content.child(module("adofaigo", "config.spark_fix.adofaigo_summary", state.adofaigo(),
                state.adofaigoPendingRestart(), adofaigoExpanded, () -> {
                    adofaigoExpanded = !adofaigoExpanded;
                    queueRebuild();
                }, () -> {
                    state.adofaigo(!state.adofaigo());
                    queueRebuild();
                }));
        if (state.adofaigo() && adofaigoExpanded) {
            FlowLayout details = details();
            details.child(label(Component.translatable(
                    "config.spark_fix.adofaigo_description"), IntegrationSettingsStyle.MUTED));
            content.child(details);
        }
        content.child(module("REI Recipe Bridge", "config.spark_fix.rei_summary", state.reiRecipeBridge(),
                state.reiRecipeBridgePendingRestart(), reiExpanded, () -> {
                    reiExpanded = !reiExpanded;
                    queueRebuild();
                }, () -> {
                    state.reiRecipeBridge(!state.reiRecipeBridge());
                    queueRebuild();
                }));
        if (state.reiRecipeBridge() && reiExpanded) {
            FlowLayout details = details();
            if (state.reiInstalled()) {
                addReiSettings(details);
            } else {
                details.child(label(Component.translatable("config.spark_fix.rei_missing"),
                        IntegrationSettingsStyle.MUTED));
            }
            content.child(details);
        }
        content.child(module("Litematica", "config.spark_fix.litematica_summary", state.litematica(),
                state.litematicaPendingRestart(), litematicaExpanded, () -> {
                    litematicaExpanded = !litematicaExpanded;
                    queueRebuild();
                }, () -> {
                    state.litematica(!state.litematica());
                    queueRebuild();
                }));
        if (state.litematica() && litematicaExpanded) {
            FlowLayout details = details();
            if (!state.litematicaInstalled()) {
                details.child(label(Component.translatable("config.spark_fix.litematica_missing"),
                        IntegrationSettingsStyle.MUTED));
            } else if (!SparkFixConfig.litematicaEnabledAtStartup()) {
                details.child(label(Component.translatable("config.spark_fix.restart_required"),
                        0xFFFFFF55));
            } else {
                var open = new FocusableWidgetComponent(new ProjectionButton(0, 0, panelWidth - 48,
                        () -> minecraft.setScreenAndShow(IntegrationSettingsRouter.createLitematica(this))));
                open.horizontalSizing(Sizing.fill());
                details.child(open);
            }
            content.child(details);
        }
        content.child(module("Structure Finder", "config.spark_fix.structure_finder_summary", state.structureFinder(),
                state.structureFinderPendingRestart(), structureFinderExpanded, () -> {
                    structureFinderExpanded = !structureFinderExpanded;
                    queueRebuild();
                }, () -> {
                    state.structureFinder(!state.structureFinder());
                    queueRebuild();
                }));
        if (state.structureFinder() && structureFinderExpanded) {
            FlowLayout details = details();
            if (!SparkFixConfig.structureFinderEnabledAtStartup()) {
                details.child(label(Component.translatable("config.spark_fix.restart_required"), 0xFFFFFF55));
            } else {
                ActionComponent open = action(Component.translatable("gui.spark_fix.structure_finder.open"),
                        () -> minecraft.setScreenAndShow(new StructureFinderScreen(this)), panelWidth - 48, 24);
                open.horizontalSizing(Sizing.fill());
                details.child(open);
            }
            content.child(details);
        }
        if (!state.adofaigo() && !state.reiRecipeBridge() && !state.litematica() && !state.structureFinder()) {
            content.child(label(Component.translatable("config.spark_fix.disabled_until_enabled"),
                    IntegrationSettingsStyle.MUTED));
        }

        scroll = new SettingsScroll(content);
        scroll.scrollbarThiccness(3);
        scroll.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0x99FFFFFF)));
        panel.child(scroll);
        FlowLayout footer = UIContainers.horizontalFlow(Sizing.fill(), Sizing.fixed(24));
        footer.horizontalAlignment(HorizontalAlignment.RIGHT);
        footer.child(action(Component.translatable("config.spark_fix.done"), this::saveAndClose, 90, 24));
        panel.child(footer);
        root.child(panel);
    }

    private LabelComponent section(String key) {
        LabelComponent heading = label(Component.translatable(key), 0xFFCAE2FF);
        heading.margins(Insets.vertical(3));
        return heading;
    }

    private FlowLayout module(String name, String description, boolean selected, boolean pending,
                              boolean expanded, Runnable fold, Runnable toggle) {
        FlowLayout card = card();
        if (selected) {
            card.cursorStyle(CursorStyle.HAND);
            // ParentUIComponent dispatches to children first. A handled toggle click never reaches here.
            card.mouseDown().subscribe((click, doubled) -> {
                if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
                fold.run();
                return true;
            });
        }
        boolean compact = panelWidth < 380;
        FlowLayout row = compact
                ? UIContainers.verticalFlow(Sizing.fill(), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(), Sizing.content());
        row.gap(8);
        row.verticalAlignment(VerticalAlignment.CENTER);
        FlowLayout text = UIContainers.verticalFlow(compact ? Sizing.fill() : Sizing.expand(), Sizing.content());
        text.gap(5);
        LabelComponent nameLabel = label(Component.literal(name.equals("adofaigo") ? "adofai" : name), 0xFFFFFFFF);
        nameLabel.maxWidth(Math.max(80, panelWidth - (compact ? 56 : 206)));
        text.child(nameLabel);
        LabelComponent summary = label(Component.translatable(description), IntegrationSettingsStyle.MUTED);
        summary.maxWidth(Math.max(80, panelWidth - (compact ? 56 : 206)));
        text.child(summary);
        boolean litematica = name.equals("Litematica");
        boolean structureFinder = name.equals("Structure Finder");
        BaseUIComponent icon = structureFinder ? new StructureFinderModuleIcon() : new IntegrationModuleIcon(
                name.equals("adofaigo") ? ADOFAIGO_ICON : litematica
                        ? state.litematicaInstalled() ? LITEMATICA_ICON : null : REI_BRIDGE_ICON,
                name.equals("adofaigo") || litematica ? 32 : 512);
        if (compact) {
            FlowLayout identity = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content());
            identity.gap(8);
            identity.verticalAlignment(VerticalAlignment.CENTER);
            identity.child(icon);
            identity.child(text);
            row.child(identity);
        } else {
            row.child(icon);
            row.child(text);
        }
        ActionComponent button = action(Component.translatable("config.spark_fix.enable_mod"), toggle, 104, 24);
        button.selected = selected;
        button.outlined = true;
        var cardFade = name.equals("adofaigo") ? adofaigoCardFade : litematica ? litematicaCardFade
                : structureFinder ? structureFinderCardFade : reiCardFade;
        card.surface((graphics, component) -> {
            boolean hovered = selected && component.isInBoundingBox(pointerX, pointerY)
                    && graphics.containsPointInScissor(pointerX, pointerY)
                    && !button.isInBoundingBox(pointerX, pointerY);
            IntegrationSettingsStyle.roundedRect(graphics, component.x(), component.y(),
                    component.width(), component.height(), 8, IntegrationSettingsStyle.blend(
                            IntegrationSettingsStyle.MODULE_CARD, IntegrationSettingsStyle.MODULE_CARD_HOVER,
                            cardFade.sample(hovered)));
        });
        button.tooltip(Component.literal((name.equals("adofaigo") ? "adofai" : name) + ": ").append(Component.translatable(
                selected ? "config.spark_fix.on" : "config.spark_fix.off")));
        FlowLayout controls = UIContainers.horizontalFlow(Sizing.content(), Sizing.fixed(24));
        controls.gap(6);
        controls.child(button);
        if (selected) {
            String disclosureKey = name.equals("adofaigo") ? "config.spark_fix.adofaigo_section"
                    : litematica ? "config.spark_fix.litematica_section"
                    : structureFinder ? "config.spark_fix.structure_finder_section" : "config.spark_fix.rei_bridge_section";
            ActionComponent disclosure = action(Component.translatable(disclosureKey), fold, 24, 24);
            disclosure.expanded = expanded;
            disclosure.tooltip(disclosure.message);
            controls.child(disclosure);
        }
        if (compact) {
            row.child(controls);
        } else {
            row.child(controls);
        }
        card.child(row);
        if (pending) card.child(label(Component.translatable("config.spark_fix.restart_required"), 0xFFFFD782));
        return card;
    }

    private FlowLayout card() {
        FlowLayout card = UIContainers.verticalFlow(Sizing.fill(), Sizing.content());
        card.padding(Insets.of(10));
        card.gap(8);
        card.surface((graphics, component) -> IntegrationSettingsStyle.roundedRect(graphics,
                component.x(), component.y(), component.width(), component.height(), 8, IntegrationSettingsStyle.MODULE_CARD));
        return card;
    }

    private FlowLayout details() {
        FlowLayout details = card();
        details.surface((graphics, component) -> IntegrationSettingsStyle.roundedRect(graphics,
                component.x(), component.y(), component.width(), component.height(), 8,
                IntegrationSettingsStyle.SETTINGS_PANEL));
        return details;
    }

    private LabelComponent label(Component text, int color) {
        LabelComponent label = UIComponents.label(text);
        label.sizing(Sizing.content(), Sizing.content());
        label.maxWidth(Math.max(100, panelWidth - 56));
        label.color(Color.ofArgb(color));
        label.shadow(true);
        return label;
    }

    private ActionComponent action(Component text, Runnable callback, int width, int height) {
        ActionComponent button = new ActionComponent(text, callback);
        button.sizing(Sizing.fixed(width), Sizing.fixed(height));
        return button;
    }

    private void queueRebuild() {
        if (this.uiAdapter == null || rebuildQueued) return;
        rebuildQueued = true;
        this.uiAdapter.rootComponent.queue(() -> {
            rebuildQueued = false;
            rebuild();
        });
    }

    private void rebuild() {
        double offset = scroll == null ? 0 : scroll.offset();
        this.uiAdapter.rootComponent.clearChildren();
        build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        scroll.restoreOffset(offset);
    }

    private void addReiSettings(FlowLayout details) {
        ReiInlineSettings settings = state.reiSettings();
        if (settings == null) {
            details.child(label(Component.translatable("config.spark_fix.rei_settings_failed"), 0xFFFFD782));
            return;
        }
        addReiToggle(details, "rei_recipe_bridge.vanilla_toggle", settings.provideVanilla(), () -> {
            settings.toggleVanilla();
            queueRebuild();
        });
        addReiToggle(details, "rei_recipe_bridge.capture_toggle", settings.captureUnlocked(), () -> {
            settings.toggleCapture();
            queueRebuild();
        });
        for (ReiInlineSettings.Line line : settings.information()) {
            details.child(label(line.text(), line.color()));
        }
        ActionComponent refresh = addReiButton(details, Component.translatable("rei_recipe_bridge.refresh"), () -> {
            settings.refresh();
            queueRebuild();
        });
        refresh.enabled = settings.canRefresh();
        refresh.cursorStyle(refresh.enabled ? CursorStyle.HAND : CursorStyle.POINTER);
        refresh.tooltip(Component.translatable(settings.canRefresh()
                ? "config.spark_fix.rei_refresh_help" : "config.spark_fix.rei_refresh_no_world"));
    }

    private void addReiToggle(FlowLayout details, String key, boolean value, Runnable callback) {
        Component text = Component.translatable(key);
        int height = Math.max(28, font.wordWrapHeight(text, panelWidth - 96) + 12);
        ActionComponent button = action(text, callback, panelWidth - 48, height);
        button.selected = value;
        button.horizontalSizing(Sizing.fill());
        button.tooltip(text.copy().append(": ").append(Component.translatable(
                value ? "config.spark_fix.on" : "config.spark_fix.off")));
        details.child(button);
    }

    private ActionComponent addReiButton(FlowLayout details, Component text, Runnable callback) {
        int height = Math.max(24, font.wordWrapHeight(text, panelWidth - 64) + 12);
        ActionComponent button = action(text, callback, panelWidth - 48, height);
        button.wrapText = true;
        button.horizontalSizing(Sizing.fill());
        details.child(button);
        return button;
    }

    private void saveAndClose() {
        state.save();
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() { saveAndClose(); }

    private static final class StructureFinderModuleIcon extends BaseUIComponent {
        private StructureFinderModuleIcon() { sizing(Sizing.fixed(32), Sizing.fixed(32)); }

        @Override public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            // Do not construct an ItemStack while owo is building its component tree:
            // 26.2 can still have unbound item components at this point. The compact
            // cracked-brick mark keeps the integration page safe during early init.
            int left = x() + 4;
            int top = y() + 4;
            graphics.fill(left, top, left + 24, top + 24, 0xFF7C8794);
            graphics.fill(left + 2, top + 2, left + 22, top + 22, 0xFFB3BBC4);
            int crack = 0xFF53606D;
            graphics.fill(left + 5, top + 5, left + 7, top + 11, crack);
            graphics.fill(left + 7, top + 10, left + 12, top + 12, crack);
            graphics.fill(left + 12, top + 12, left + 14, top + 18, crack);
            graphics.fill(left + 14, top + 17, left + 20, top + 19, crack);
        }
    }

    /** owo routes drag and keyboard events to the focused component, not the clicked widget. */
    private static final class FocusableWidgetComponent extends VanillaWidgetComponent {
        private final AbstractWidget widget;

        private FocusableWidgetComponent(AbstractWidget widget) {
            super(widget);
            this.widget = widget;
        }

        @Override
        public boolean canFocus(FocusSource source) {
            return widget.active && widget.visible;
        }

        @Override
        public void onFocusGained(FocusSource source) {
            super.onFocusGained(source);
            widget.setFocused(true);
        }

        @Override
        public void onFocusLost() {
            super.onFocusLost();
            widget.setFocused(false);
        }
    }

    private static final class SettingsScroll extends ScrollContainer<FlowLayout> {
        private SettingsScroll(FlowLayout content) {
            super(ScrollDirection.VERTICAL, Sizing.fill(), Sizing.expand(), content);
        }

        private double offset() { return scrollOffset; }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            return super.onMouseScroll(mouseX, mouseY, amount * SparkFixConfig.scrollSensitivity());
        }

        private void restoreOffset(double offset) {
            scrollBy(offset - scrollOffset, true, false);
        }
    }

    /** Pack-aware buttons and rounded switches; scrolling never activates a control. */
    private static final class ActionComponent extends BaseUIComponent {
        private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
        private static final Identifier HOVERED = Identifier.withDefaultNamespace("widget/button_highlighted");
        private static final Identifier DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
        private final Component message;
        private final Runnable callback;
        private Boolean selected;
        private Boolean expanded;
        private boolean outlined;
        private final IntegrationSettingsStyle.HoverFade hoverFade = new IntegrationSettingsStyle.HoverFade();
        private boolean focused;
        private boolean wrapText;
        private boolean enabled = true;

        private ActionComponent(Component message, Runnable callback) {
            this.message = message;
            this.callback = callback;
            cursorStyle(CursorStyle.HAND);
        }

        @Override
        public boolean canFocus(FocusSource source) { return enabled; }

        @Override
        public void onFocusGained(FocusSource source) {
            super.onFocusGained(source);
            focused = true;
        }

        @Override
        public void onFocusLost() {
            super.onFocusLost();
            focused = false;
        }

        @Override
        public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
            boolean hovered = focused || isInBoundingBox(mouseX, mouseY);
            var font = Minecraft.getInstance().font;
            if (expanded != null) {
                IntegrationSettingsStyle.disclosure(graphics, x(), y(), width(), height(), expanded, hovered);
                return;
            }
            if (selected == null) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, !enabled ? DISABLED : hovered ? HOVERED : BUTTON,
                        x(), y(), width(), height());
                if (wrapText) {
                    var lines = font.split(message, Math.max(1, width() - 16));
                    int textY = y() + (height() - lines.size() * font.lineHeight) / 2;
                    for (var line : lines) {
                        graphics.centeredText(font, line, x() + width() / 2, textY, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
                        textY += font.lineHeight;
                    }
                } else {
                    graphics.centeredText(font, message, x() + width() / 2, y() + (height() - 8) / 2, 0xFFFFFFFF);
                }
                return;
            }
            float hoverAmount = hoverFade.sample(hovered);
            IntegrationSettingsStyle.toggle(graphics, font, message, x(), y(), width(), height(), selected, hoverAmount);
            if (outlined) IntegrationSettingsStyle.moduleToggleOutline(graphics,
                    x(), y(), width(), height(), hoverAmount);
        }

        @Override
        public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
            if (!enabled || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            callback.run();
            return true;
        }

        @Override
        public boolean onKeyPress(KeyEvent input) {
            if (!enabled || !input.isSelection()) return false;
            callback.run();
            return true;
        }
    }
}
