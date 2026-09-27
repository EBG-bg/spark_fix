package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Adapts MaLiLib's own key recorder, settings icon and tooltips without a required dependency. */
final class LitematicaHotkeyWidget extends AbstractWidget {
    private static Api sharedApi;
    private final Api api;
    private final Object keybind;
    private final Object resetTarget;
    private final Object keyButton;
    private Object settingsIcon;
    private final String name;
    private final Screen parent;
    private final Consumer<Screen> openChild;
    private final Runnable changed;
    private final Supplier<ScreenRectangle> viewport;
    private final List<Runnable> changeListeners = new ArrayList<>();
    private final SettingsIconButton reset;
    private final int keyWidth;
    private final int settingsX;
    private final int settingsY;
    private final Tools tools;
    private boolean capturing;

    static int heightFor(int width) {
        return width >= 96 ? 20 : width >= 40 ? 44 : 66;
    }

    static AbstractWidget create(Object option, String name, int width, boolean toolsInHeader, Screen parent,
                                 Consumer<Screen> openChild, Runnable changed, Supplier<ScreenRectangle> viewport) {
        try {
            if (sharedApi == null) sharedApi = new Api();
            return new LitematicaHotkeyWidget(sharedApi, option, name, width, toolsInHeader, parent, openChild, changed, viewport);
        } catch (ReflectiveOperationException | LinkageError exception) {
            SparkFixClient.LOGGER.warn("Could not create native MaLiLib hotkey editor for {}", name, exception);
            Button unavailable = Button.builder(Component.translatable("config.spark_fix.litematica_hotkey_unavailable"),
                    ignored -> { }).bounds(0, 0, width, toolsInHeader ? 20 : heightFor(width)).build();
            unavailable.active = false;
            return unavailable;
        }
    }

    private LitematicaHotkeyWidget(Api api, Object option, String name, int width, boolean toolsInHeader, Screen parent,
                                  Consumer<Screen> openChild, Runnable changed, Supplier<ScreenRectangle> viewport)
            throws ReflectiveOperationException {
        super(0, 0, width, toolsInHeader ? 20 : heightFor(width), Component.translatable("config.spark_fix.litematica_hotkey", name));
        this.api = api;
        this.keybind = LitematicaConfigDiscovery.keybind(option);
        resetTarget = LitematicaConfigDiscovery.canReset(option) ? option : keybind;
        this.name = name;
        this.parent = parent;
        this.openChild = openChild;
        this.changed = changed;
        this.viewport = viewport;
        keyWidth = toolsInHeader ? width : width >= 96 ? width - 44 : width;
        settingsX = toolsInHeader ? 0 : width >= 96 ? keyWidth + 2 : width >= 40 ? 0 : Math.max(0, (width - 20) / 2);
        settingsY = toolsInHeader || width >= 96 ? 0 : 24;
        int resetX = toolsInHeader ? 24 : width >= 40 ? width - 18 : Math.max(0, (width - 18) / 2);
        int resetY = toolsInHeader || width >= 96 ? 1 : width >= 40 ? 25 : 48;
        Object host = Proxy.newProxyInstance(api.hostType.getClassLoader(), new Class<?>[]{api.hostType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setActiveKeybindButton" -> {
                        if (args[0] == null) finishCapture();
                        else {
                            capturing = true;
                            call(api.selected, args[0]);
                        }
                        yield null;
                    }
                    case "addKeybindChangeListener" -> { changeListeners.add((Runnable) args[0]); yield null; }
                    case "clearOptions" -> { finishCapture(); yield null; }
                    case "getModId" -> "spark_fix";
                    case "getConfigs" -> List.of();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "spark_fix native hotkey host";
                    default -> null;
                });
        keyButton = api.keyButton.newInstance(0, 0, keyWidth, 20, keybind, host);
        refreshSettingsIcon();
        reset = new SettingsIconButton(resetX, resetY, 18, SettingsIconButton.Icon.RESET,
                Component.translatable(LitematicaConfigDiscovery.isBoolean(option)
                        ? "config.spark_fix.litematica_option_reset_hotkey" : "config.spark_fix.litematica_option_reset", name), () -> {
            finishCapture();
            LitematicaConfigDiscovery.resetToDefault(resetTarget);
            refreshKeys();
        }, true) {
            @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int x, int y) { }
        };
        tools = toolsInHeader ? new Tools() : null;
        updateReset();
    }

    Tools tools() { return tools; }

    boolean isCapturing() { return capturing; }

    void finishCapture() {
        if (!capturing) return;
        capturing = false;
        call(api.clearSelection, keyButton);
        refreshKeys();
        changeListeners.forEach(Runnable::run);
    }

    private void refreshKeys() {
        Object manager = call(api.manager, null);
        call(api.updateUsedKeys, manager);
        refreshDisplay();
        changed.run();
    }

    void refreshDisplay() {
        call(api.updateDisplay, keyButton);
        updateReset();
    }

    private void updateReset() {
        if (reset != null) reset.active = LitematicaConfigDiscovery.isModified(resetTarget);
    }

    private void refreshSettingsIcon() throws ReflectiveOperationException {
        settingsIcon = api.settingsIcon.newInstance(settingsX, settingsY, 20, 20, keybind, name, null, null);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!visible || !active || !isMouseOver(click.x(), click.y())) return false;
        if (over(click.x(), click.y(), 0, 0, keyWidth, 20)) {
            if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && !capturing) return false;
            boolean handled = Boolean.TRUE.equals(call(api.click, keyButton, click, doubled));
            updateReset();
            return handled;
        }
        return tools == null && clickTools(click, doubled);
    }

    private void openSettings() {
        finishCapture();
        try {
            Screen dialog = (Screen) api.settingsScreen.newInstance(keybind, name, null, parent);
            openChild.accept(dialog);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not open native keybind settings", exception);
        }
    }

    private boolean clickTools(MouseButtonEvent click, boolean doubled) {
        if (over(click.x(), click.y(), settingsX, settingsY, 20, 20)) {
            finishCapture();
            try {
                if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    openSettings();
                    return true;
                }
                if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    // The original widget expects a MaLiLib list to refresh. This
                    // page has cards, so recreate just the icon after resetting.
                    call(api.resetSettings, keybind);
                    refreshSettingsIcon();
                    refreshKeys();
                    return true;
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not open native keybind settings", exception);
            }
        }
        if (reset.isMouseOver(click.x(), click.y())) {
            updateReset();
            reset.mouseClicked(click, doubled);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!capturing) {
            if (!isFocused() || !event.isSelection()) return false;
            capturing = true;
            call(api.selected, keyButton);
            return true;
        }
        call(api.keyPressed, keyButton, event.key());
        updateReset();
        return true;
    }

    @Override public boolean charTyped(CharacterEvent event) { return capturing; }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) finishCapture();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        renderParts(graphics, mouseX, mouseY, partialTick, true);
    }

    private void renderParts(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, boolean keyPart) {
        GuiGraphicsExtractor context = (GuiGraphicsExtractor) call(api.context, null, graphics);
        ScreenRectangle clip = viewport.get();
        // GuiContext shares the pose but creates its own scissor stack. Carry
        // over the page viewport so native controls cannot escape the card list.
        graphics.pose().pushMatrix();
        graphics.pose().identity();
        context.enableScissor(clip.left(), clip.top(), clip.right(), clip.bottom());
        graphics.pose().popMatrix();
        if (keyPart) renderNative(graphics, context, keyButton, 0, 0, keyWidth, 20, mouseX, mouseY);
        if (!keyPart || tools == null) renderNative(graphics, context, settingsIcon, settingsX, settingsY, 20, 20, mouseX, mouseY);
        context.disableScissor();
        updateReset();
        if (!keyPart || tools == null) reset.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderNative(GuiGraphicsExtractor graphics, GuiGraphicsExtractor context, Object widget,
                              int x, int y, int width, int height, int mouseX, int mouseY) {
        graphics.enableScissor(x, y, x + width, y + height);
        context.enableScissor(x, y, x + width, y + height);
        call(api.render, widget, context, mouseX, mouseY, over(mouseX, mouseY, x, y, width, height));
        context.disableScissor();
        graphics.disableScissor();
    }

    void renderHover(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        renderHover(graphics, x, y, mouseX, mouseY, true);
    }

    private void renderHover(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, boolean keyPart) {
        boolean showTools = !keyPart || tools == null;
        if (showTools && reset.isMouseOver(mouseX - x, mouseY - y)) {
            graphics.setTooltipForNextFrame(net.minecraft.client.Minecraft.getInstance().font,
                    reset.getMessage(), mouseX, mouseY);
            return;
        }
        Object widget;
        int localX;
        int localY;
        if (keyPart && over(mouseX, mouseY, x, y, keyWidth, 20)) {
            widget = keyButton;
            localX = 0;
            localY = 0;
        } else if (showTools && over(mouseX, mouseY, x + settingsX, y + settingsY, 20, 20)) {
            widget = settingsIcon;
            localX = settingsX;
            localY = settingsY;
        } else return;
        Object context = call(api.context, null, graphics);
        call(api.position, widget, x + localX, y + localY);
        try {
            call(api.hover, widget, context, mouseX, mouseY, true);
        } finally {
            call(api.position, widget, localX, localY);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

    /** The same native actions can occupy spare header space without covering the title or key recorder. */
    final class Tools extends AbstractWidget {
        private boolean resetFocused;

        Tools() { super(0, 0, 42, 20, Component.translatable("config.spark_fix.litematica_hotkey_tools", name)); }

        @Override public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            return visible && active && isMouseOver(click.x(), click.y()) && clickTools(click, doubled);
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_TAB) {
                if (event.hasShiftDown() ? !resetFocused : resetFocused) return false;
                resetFocused = !resetFocused;
                reset.setFocused(resetFocused);
                return true;
            }
            if (!isFocused()) return false;
            if (resetFocused) return reset.keyPressed(event);
            if (event.isSelection()) { openSettings(); return true; }
            return false;
        }

        @Override public void setFocused(boolean focused) {
            super.setFocused(focused);
            reset.setFocused(focused && resetFocused);
        }

        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
            renderParts(graphics, mouseX, mouseY, tick, false);
        }

        void renderHover(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
            LitematicaHotkeyWidget.this.renderHover(graphics, x, y, mouseX, mouseY, false);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private static boolean over(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("MaLiLib widget call failed: " + method.getName(), exception);
        }
    }

    private static final class Api {
        final Class<?> hostType = Class.forName("fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui");
        final Class<?> keyType = Class.forName("fi.dy.masa.malilib.hotkeys.IKeybind");
        final Class<?> buttonType = Class.forName("fi.dy.masa.malilib.gui.button.ConfigButtonKeybind");
        final Class<?> widgetType = Class.forName("fi.dy.masa.malilib.gui.widgets.WidgetBase");
        final Class<?> contextType = Class.forName("fi.dy.masa.malilib.render.GuiContext");
        final Class<?> dialogType = Class.forName("fi.dy.masa.malilib.gui.interfaces.IDialogHandler");
        final Constructor<?> keyButton = buttonType.getConstructor(int.class, int.class, int.class, int.class, keyType, hostType);
        final Constructor<?> settingsIcon = Class.forName("fi.dy.masa.malilib.gui.widgets.WidgetKeybindSettings")
                .getConstructor(int.class, int.class, int.class, int.class, keyType, String.class,
                        Class.forName("fi.dy.masa.malilib.gui.widgets.WidgetListBase"), dialogType);
        final Constructor<?> settingsScreen = Class.forName("fi.dy.masa.malilib.gui.GuiKeybindSettings")
                .getConstructor(keyType, String.class, dialogType, Screen.class);
        final Method context = contextType.getMethod("fromGuiGraphics", GuiGraphicsExtractor.class);
        final Method render = widgetType.getMethod("render", contextType, int.class, int.class, boolean.class);
        final Method hover = widgetType.getMethod("postRenderHovered", contextType, int.class, int.class, boolean.class);
        final Method position = widgetType.getMethod("setPosition", int.class, int.class);
        final Method click = widgetType.getMethod("onMouseClicked", MouseButtonEvent.class, boolean.class);
        final Method selected = buttonType.getMethod("onSelected");
        final Method clearSelection = buttonType.getMethod("onClearSelection");
        final Method keyPressed = buttonType.getMethod("onKeyPressed", int.class);
        final Method updateDisplay = buttonType.getMethod("updateDisplayString");
        final Method resetSettings = keyType.getMethod("resetSettingsToDefaults");
        final Method manager = Class.forName("fi.dy.masa.malilib.event.InputEventHandler").getMethod("getKeybindManager");
        final Method updateUsedKeys = Class.forName("fi.dy.masa.malilib.hotkeys.IKeybindManager").getMethod("updateUsedKeys");

        Api() throws ReflectiveOperationException { }
    }
}
