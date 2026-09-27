package dev.codex.spark_fix;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Card adapters over the live MaLiLib range; native setters retain render invalidation and world bounds. */
final class LitematicaRenderLayerSettings {
    private final Object range;
    private final Runnable layoutChanged;
    private final Runnable saveAction;
    private final List<Setting> settings;
    private boolean dirty;

    LitematicaRenderLayerSettings(Object range, Runnable layoutChanged, Runnable saveAction) {
        this.range = range;
        this.layoutChanged = layoutChanged;
        this.saveAction = saveAction;
        settings = List.of(new ChoiceSetting("mode", false), new ChoiceSetting("axis", true),
                new NumberSetting("single", "SINGLE_LAYER", "getLayerSingle", "setLayerSingle"),
                new NumberSetting("above", "ALL_ABOVE", "getLayerAbove", "setLayerAbove"),
                new NumberSetting("below", "ALL_BELOW", "getLayerBelow", "setLayerBelow"),
                new NumberSetting("min", "LAYER_RANGE", "getLayerRangeMin", "setLayerRangeMin"),
                new NumberSetting("max", "LAYER_RANGE", "getLayerRangeMax", "setLayerRangeMax"),
                new ToggleSetting("move_min", "getMoveLayerRangeMin", "toggleHotkeyMoveRangeMin"),
                new ToggleSetting("move_max", "getMoveLayerRangeMax", "toggleHotkeyMoveRangeMax"),
                new PositionAction());
    }

    static LitematicaRenderLayerSettings load(Runnable layoutChanged) {
        try {
            Class<?> manager = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = manager.getMethod("getRenderLayerRange").invoke(null);
            Method save = manager.getMethod("save");
            return new LitematicaRenderLayerSettings(range, layoutChanged, () -> {
                try { save.invoke(null); }
                catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Could not save render layers", exception);
                }
            });
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            SparkFixClient.LOGGER.warn("Could not load inline Litematica render layers", exception);
            return null;
        }
    }

    List<LitematicaConfigDiscovery.DiscoveredGroup> attach(List<LitematicaConfigDiscovery.DiscoveredGroup> groups) {
        List<LitematicaConfigDiscovery.DiscoveredGroup> result = new ArrayList<>();
        for (var group : groups) {
            if (!group.id().equals("litematica")) { result.add(group); continue; }
            List<Object> options = new ArrayList<>(group.options());
            // Append only: existing configuration indices and saved names/favorites remain valid.
            options.addAll(settings);
            result.add(new LitematicaConfigDiscovery.DiscoveredGroup(group.id(), group.handler(), options));
        }
        return List.copyOf(result);
    }

    void save() {
        if (!dirty) return;
        try { saveAction.run(); dirty = false; }
        catch (RuntimeException exception) { SparkFixClient.LOGGER.warn("Could not save render layer changes", exception); }
    }

    private Object call(String name, Object... arguments) {
        try {
            for (Method method : range.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                    return method.invoke(range, arguments);
                }
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not access render layer " + name, exception);
        }
    }

    private String mode() { return ((Enum<?>) call("getLayerMode")).name(); }

    abstract class Setting {
        private final String id;
        Setting(String id) { this.id = id; }
        public String getName() { return "spark_fix.render_layer." + id; }
        public String getPrettyName() { return Component.translatable("config.spark_fix.litematica_layer_" + id).getString(); }
        public Component getCommentComponent() { return Component.translatable("config.spark_fix.litematica_layer_" + id + "_hint"); }
        public boolean visible() { return true; }
    }

    final class ChoiceSetting extends Setting {
        private final boolean axis;
        ChoiceSetting(String id, boolean axis) { super(id); this.axis = axis; }
        public Choice getOptionListValue() { return new Choice((Enum<?>) call(axis ? "getAxis" : "getLayerMode"), axis); }
        public void setOptionListValue(Choice value) {
            call(axis ? "setAxis" : "setLayerMode", value.value());
            dirty = true;
            layoutChanged.run();
        }
        @Override public boolean visible() { return !axis || !mode().equals("ALL"); }
    }

    record Choice(Enum<?> value, boolean axis) {
        public String getDisplayName() {
            if (axis) return value.name();
            try { return (String) value.getClass().getMethod("getDisplayName").invoke(value); }
            catch (ReflectiveOperationException exception) { return value.name(); }
        }
        public Choice cycle(boolean forward) {
            Object[] values = value.getDeclaringClass().getEnumConstants();
            return new Choice((Enum<?>) values[Math.floorMod(value.ordinal() + (forward ? 1 : -1), values.length)], axis);
        }
    }

    final class NumberSetting extends Setting {
        private final String mode;
        private final String getter;
        private final String setter;
        private boolean slider;
        NumberSetting(String id, String mode, String getter, String setter) {
            super(id); this.mode = mode; this.getter = getter; this.setter = setter;
        }
        @Override public boolean visible() { return mode().equals(mode); }
        public int getIntegerValue() { return (Integer) call(getter); }
        public void setIntegerValue(int value) { call(setter, value); dirty = true; }
        public int getMinIntegerValue() { return (Integer) call("getClampedValue", Integer.MIN_VALUE, call("getAxis")); }
        public int getMaxIntegerValue() { return (Integer) call("getClampedValue", Integer.MAX_VALUE, call("getAxis")); }
        public String getType() { return "INTEGER"; }
        public String getStringValue() { return Integer.toString(getIntegerValue()); }
        public void setValueFromString(String value) { setIntegerValue(Integer.parseInt(value)); }
        public boolean shouldUseSlider() { return slider; }
        public void toggleUseSlider() { slider = !slider; }
    }

    final class ToggleSetting extends Setting {
        private final String getter;
        private final String toggle;
        ToggleSetting(String id, String getter, String toggle) { super(id); this.getter = getter; this.toggle = toggle; }
        @Override public boolean visible() { return mode().equals("LAYER_RANGE"); }
        public boolean getBooleanValue() { return Boolean.TRUE.equals(call(getter)); }
        public void setValueFromString(String value) {
            if (Boolean.parseBoolean(value) != getBooleanValue()) { call(toggle); dirty = true; }
        }
    }

    final class PositionAction extends Setting {
        PositionAction() { super("here"); }
        @Override public boolean visible() { return !mode().equals("ALL"); }
        boolean enabled() { return Minecraft.getInstance().player != null; }
        void run() {
            var client = Minecraft.getInstance();
            if (!enabled()) return;
            var camera = client.getCameraEntity();
            call("setToPosition", camera != null ? camera : client.player);
            dirty = true;
            layoutChanged.run();
        }
    }
}
