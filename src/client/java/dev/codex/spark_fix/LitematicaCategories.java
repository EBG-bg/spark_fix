package dev.codex.spark_fix;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads native tab lists without constructing a config screen or changing its selected tab. */
final class LitematicaCategories {
    record Category(String id, String group, Component label, Set<Object> options) { }

    static List<Category> discover(List<LitematicaConfigDiscovery.DiscoveredGroup> groups) {
        List<Category> result = new ArrayList<>();
        for (var group : groups) {
            if (group.handler() == null) continue;
            try {
            Class<?> owner = group.handler().getClass();
            String configPackage = owner.getPackageName();
            String root = configPackage.endsWith(".config") ? configPackage.substring(0, configPackage.length() - 7) : configPackage;
            Class<?> tabs = firstClass(owner.getClassLoader(), configPackage + ".ConfigUi$Tab",
                    root + ".gui.ConfigUi$Tab", root + ".gui.GuiConfigs$ConfigGuiTab");
            if (tabs != null && tabs.isEnum()) {
                for (Object tab : tabs.getEnumConstants()) {
                    String name = ((Enum<?>) tab).name();
                    if (name.equals("ALL")) continue;
                    List<Object> entries = new ArrayList<>();
                    try {
                        if (tab.getClass().getMethod("getConfigs").invoke(tab) instanceof Iterable<?> configs) {
                            configs.forEach(entries::add);
                        }
                    } catch (ReflectiveOperationException ignored) { }
                    if (entries.isEmpty()) {
                        collectCategory(owner, name, entries);
                        Class<?> sibling = firstClass(owner.getClassLoader(), configPackage + "." + camel(name));
                        if (sibling != null) collectCategory(sibling, name, entries);
                    }
                    if (name.equals("RENDER_LAYERS")) entries.addAll(group.options().stream()
                            .filter(LitematicaRenderLayerSettings.Setting.class::isInstance).toList());
                    add(result, group, name, tabLabel(tab), entries);
                }
            } else {
                // Other addons can expose their category lists as static fields or getter methods.
                for (Class<?> nested : owner.getDeclaredClasses()) {
                    List<Object> entries = new ArrayList<>();
                    collectCategory(nested, nested.getSimpleName(), entries);
                    add(result, group, nested.getSimpleName(), Component.literal(camel(nested.getSimpleName())), entries);
                }
            }
            Set<Object> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
            result.stream().filter(category -> category.group().equals(group.id())).forEach(category -> assigned.addAll(category.options()));
            List<Object> remaining = group.options().stream().filter(option -> !assigned.contains(option)).toList();
            add(result, group, "OTHER", Component.translatable("config.spark_fix.litematica_category_other"), remaining);
            } catch (LinkageError | RuntimeException exception) {
                SparkFixClient.LOGGER.warn("Could not read native setting categories for {}", group.id(), exception);
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Category> target, LitematicaConfigDiscovery.DiscoveredGroup group, String name,
                            Component label, List<Object> entries) {
        Set<Object> available = Collections.newSetFromMap(new IdentityHashMap<>());
        available.addAll(group.options());
        Set<Object> options = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object entry : entries) flatten(entry, available, options, visited);
        if (!options.isEmpty()) target.add(new Category(group.id() + ":" + name, group.id(), label, options));
    }

    private static void flatten(Object value, Set<Object> available, Set<Object> output, Set<Object> visited) {
        if (value == null || !visited.add(value)) return;
        if (available.contains(value)) output.add(value);
        if (value instanceof Iterable<?> list) {
            for (Object entry : list) flatten(entry, available, output, visited);
        } else {
            try {
                Field children = value.getClass().getField("subConfigs");
                flatten(children.get(value), available, output, visited);
            } catch (ReflectiveOperationException ignored) { }
        }
    }

    private static void collectCategory(Class<?> owner, String category, List<Object> output) {
        String wanted = normalized(category);
        boolean matchingOwner = normalized(owner.getSimpleName()).equals(wanted);
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Iterable.class.isAssignableFrom(field.getType())) continue;
            String fieldName = normalized(field.getName());
            if (!matchingOwner && !fieldName.equals(wanted) && !fieldName.equals(wanted + "options")
                    && !(wanted.equals("hotkeys") && fieldName.equals("hotkeylist"))) continue;
            try {
                field.trySetAccessible();
                if (field.get(null) instanceof Iterable<?> entries) entries.forEach(output::add);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || !Iterable.class.isAssignableFrom(method.getReturnType())) continue;
            String methodName = normalized(method.getName()).replaceFirst("^(get|add)", "");
            if (!methodName.equals(wanted)) continue;
            try {
                method.trySetAccessible();
                if (method.invoke(null) instanceof Iterable<?> entries) entries.forEach(output::add);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (normalized(nested.getSimpleName()).equals(wanted)) collectCategory(nested, category, output);
        }
    }

    private static Component tabLabel(Object tab) {
        for (String method : List.of("getDisplayName", "getName")) {
            try {
                Object label = tab.getClass().getMethod(method).invoke(tab);
                if (label != null && !label.toString().isBlank()) return Component.literal(label.toString());
            } catch (ReflectiveOperationException ignored) { }
        }
        try {
            return Component.literal(String.valueOf(tab.getClass().getField("name").get(tab)));
        } catch (ReflectiveOperationException ignored) {
            return Component.literal(camel(((Enum<?>) tab).name()));
        }
    }

    private static Class<?> firstClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, loader); }
            catch (ClassNotFoundException | LinkageError ignored) { }
        }
        return null;
    }

    private static String normalized(String name) { return name.replace("_", "").toLowerCase(Locale.ROOT); }
    private static String camel(String name) {
        if (!name.contains("_") && !name.equals(name.toUpperCase(Locale.ROOT))) return name;
        StringBuilder result = new StringBuilder();
        for (String word : name.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
