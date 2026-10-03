package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reflective bridge for MaLiLib configuration handlers owned by Litematica addons. */
final class LitematicaConfigDiscovery {
    private static final String CONFIG_BASE = "fi.dy.masa.malilib.config.IConfigBase";
    private static final String MANAGER = "fi.dy.masa.malilib.config.ConfigManager";
    private static final Set<Object> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ClassValue<Accessors> ACCESSORS = new ClassValue<>() {
        @Override protected Accessors computeValue(Class<?> type) {
            Map<String, Method> getters = new java.util.HashMap<>();
            Map<String, Method> setters = new java.util.HashMap<>();
            for (Method method : type.getMethods()) {
                Map<String, Method> methods;
                if (method.getParameterCount() == 0) methods = getters;
                else if (method.getParameterCount() == 1
                        && (!method.getName().equals("setValueFromString") || method.getParameterTypes()[0] == String.class)) {
                    methods = setters;
                } else continue;
                if (!methods.containsKey(method.getName())) {
                    method.trySetAccessible();
                    methods.put(method.getName(), method);
                }
            }
            return new Accessors(Map.copyOf(getters), Map.copyOf(setters));
        }
    };

    private record Accessors(Map<String, Method> getters, Map<String, Method> setters) { }

    private LitematicaConfigDiscovery() {
    }

    static List<DiscoveredGroup> discover() {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return List.of();
        try {
            Class<?> base = Class.forName(CONFIG_BASE);
            Class<?> managerClass = Class.forName(MANAGER);
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Map<?, ?> handlers = handlers(managerClass, manager);
            if (handlers == null) return List.of();
            Set<String> relatedIds = relatedModIds();
            List<DiscoveredGroup> groups = new ArrayList<>();
            for (Map.Entry<?, ?> entry : handlers.entrySet()) {
                String id = String.valueOf(entry.getKey());
                Object handler = entry.getValue();
                if (!relatedIds.contains(id) && !relatedHandler(id, handler)) continue;
                List<Object> options = new ArrayList<>();
                SEEN.clear();
                collect(handler == null ? null : handler.getClass(), handler, base, options, 0, true);
                if (handler != null) collectPackage(handler.getClass().getPackageName(), handler.getClass().getClassLoader(),
                        base, options);
                if (!options.isEmpty()) groups.add(new DiscoveredGroup(id, handler, options));
            }
            return groups;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not discover MaLiLib configuration options", exception);
            return List.of();
        }
    }

    private static boolean relatedHandler(String id, Object handler) {
        StringBuilder identity = new StringBuilder(id);
        if (handler != null) identity.append(' ').append(handler.getClass().getName());
        FabricLoader.getInstance().getModContainer(id).ifPresent(container ->
                identity.append(' ').append(container.getMetadata().getName()));
        String normalized = identity.toString().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("litematica") || normalized.contains("schematic")
                || normalized.contains("printer");
    }

    /** Resolve handler ownership without changing the handler IDs used in saved option keys. */
    static List<DiscoveredModule> modules(List<DiscoveredGroup> groups) {
        List<ModContainer> loaded = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        Map<String, DiscoveredModule> modules = new java.util.LinkedHashMap<>();
        for (DiscoveredGroup group : groups) {
            if (group.options().isEmpty()) continue;
            ModContainer owner = owningMod(group, loaded);
            String id = owner == null ? group.id() : owner.getMetadata().getId();
            DiscoveredModule module = modules.computeIfAbsent(id, ignored -> new DiscoveredModule(id,
                    owner == null ? id : owner.getMetadata().getName(), owner, new ArrayList<>()));
            if (!module.groupIds().contains(group.id())) module.groupIds().add(group.id());
        }
        return List.copyOf(modules.values());
    }

    private static ModContainer owningMod(DiscoveredGroup group, List<ModContainer> loaded) {
        ModContainer exact = FabricLoader.getInstance().getModContainer(group.id()).orElse(null);
        if (exact != null) return exact;
        for (ModContainer container : loaded) {
            if (container.getMetadata().getId().replace('-', '_').equals(group.id().replace('-', '_'))) return container;
        }
        if (group.handler() != null) {
            String classPath = group.handler().getClass().getName().replace('.', '/') + ".class";
            for (ModContainer container : loaded) {
                if (container.findPath(classPath).isPresent()) return container;
            }
        }
        return null;
    }

    private static void collectPackage(String packageName, ClassLoader loader, Class<?> base, List<Object> output) {
        String resourcePath = packageName.replace('.', '/');
        Set<String> classNames = new java.util.LinkedHashSet<>();
        try {
            Enumeration<URL> resources = loader.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("jar".equals(resource.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) resource.openConnection();
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(resourcePath + "/") && name.endsWith(".class")
                                    && name.indexOf('$', resourcePath.length()) < 0) {
                                classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                            }
                        }
                    }
                } else if ("file".equals(resource.getProtocol())) {
                    Path directory = Path.of(resource.toURI());
                    try (var paths = Files.list(directory)) {
                        paths.filter(path -> path.getFileName().toString().endsWith(".class"))
                                .forEach(path -> classNames.add(packageName + "."
                                        + path.getFileName().toString().replaceFirst("\\.class$", "")));
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not enumerate MaLiLib config package {}", packageName, exception);
        }
        for (String name : classNames) {
            if (!name.toLowerCase().contains("config") && !name.toLowerCase().contains("hotkey")) continue;
            try {
                Class<?> type = Class.forName(name, false, loader);
                collect(type, null, base, output, 0, false);
            } catch (LinkageError | ClassNotFoundException | RuntimeException ignored) {
            }
        }
    }

    private static Map<?, ?> handlers(Class<?> managerClass, Object manager) throws ReflectiveOperationException {
        try {
            Field field = managerClass.getDeclaredField("configHandlers");
            field.setAccessible(true);
            Object value = field.get(manager);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Set<String> relatedModIds() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        ids.add("litematica");
        boolean changed;
        do {
            changed = false;
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                var metadata = container.getMetadata();
                if (ids.contains(metadata.getId())) continue;
                for (ModDependency dependency : metadata.getDepends()) {
                    if (ids.contains(dependency.getModId())) {
                        ids.add(metadata.getId());
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return ids;
    }

    private static void collect(Class<?> owner, Object instance, Class<?> base,
                                List<Object> output, int depth, boolean includeStaticMethods) {
        if (owner == null || depth > 4) return;
        for (Field field : owner.getDeclaredFields()) {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            if (!isStatic && instance == null) continue;
            if (isStatic && !mayContainOptions(field.getType(), base)) continue;
            try {
                field.setAccessible(true);
                collectValue(field.get(isStatic ? null : instance), base, output);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (includeStaticMethods) {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !Modifier.isStatic(method.getModifiers())) continue;
                if (!(Iterable.class.isAssignableFrom(method.getReturnType())
                        || Map.class.isAssignableFrom(method.getReturnType()) || method.getReturnType().isArray())) continue;
                try {
                    method.setAccessible(true);
                    collectValue(method.invoke(null), base, output);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        for (Class<?> nested : owner.getDeclaredClasses()) {
            collect(nested, null, base, output, depth + 1, false);
        }
    }

    private static boolean mayContainOptions(Class<?> type, Class<?> base) {
        return base.isAssignableFrom(type)
                || type.isArray() && base.isAssignableFrom(type.getComponentType())
                || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type);
    }

    private static void collectValue(Object value, Class<?> base, List<Object> output) {
        if (value == null) return;
        if (base.isInstance(value)) {
            if (SEEN.add(value)) output.add(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) collectValue(item, base, output);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collectValue(item, base, output);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) collectValue(Array.get(value, i), base, output);
        }
    }

    static void save(List<DiscoveredGroup> groups) {
        for (DiscoveredGroup group : groups) {
            if (!invokeNoArg(group.handler(), "save")) {
                SparkFixClient.LOGGER.warn("Could not save MaLiLib configuration handler {}", group.id());
            }
        }
    }

    static String name(Object option) {
        // Addons can translate only the native GUI accessor and leave getTranslatedName raw.
        return stringCall(option, "getConfigGuiDisplayName", "getTranslatedName", "getPrettyName", "getName");
    }

    static String stableName(Object option) {
        return stringCall(option, "getName", "getConfigName", "getNameKey", "getInternalName");
    }

    static String comment(Object option) {
        return commentComponent(option).getString();
    }

    static Component commentComponent(Object option) {
        try {
            Method getter = find(option, "getCommentComponent");
            if (getter != null && getter.invoke(option) instanceof Component comment && !comment.getString().isBlank()) {
                return comment.copy();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return Component.literal(stringCall(option, "getComment"));
    }

    static boolean isOptionList(Object option) {
        return find(option, "getOptionListValue") != null && setter(option, "setOptionListValue") != null;
    }

    static boolean isStringList(Object option) {
        Method getStrings = find(option, "getStrings");
        Method getDefaults = find(option, "getDefaultStrings");
        Method setStrings = setter(option, "setStrings");
        return stringCall(option, "getType").equalsIgnoreCase("STRING_LIST")
                && getStrings != null && List.class.isAssignableFrom(getStrings.getReturnType())
                && getDefaults != null && List.class.isAssignableFrom(getDefaults.getReturnType())
                && setStrings != null && setStrings.getParameterTypes()[0] == List.class;
    }

    static List<String> stringListValue(Object option) {
        try {
            Object value = find(option, "getStrings").invoke(option);
            if (value instanceof List<?> entries) {
                List<String> result = new ArrayList<>(entries.size());
                for (Object entry : entries) if (entry instanceof String text) result.add(text);
                return result;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not read dynamic MaLiLib string list", exception);
        }
        return List.of();
    }

    static boolean setStringListValue(Object option, List<String> values) {
        try {
            setter(option, "setStrings").invoke(option, new ArrayList<>(values));
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.warn("Could not update dynamic MaLiLib string list {}", stableName(option), exception);
            return false;
        }
    }

    static boolean isNumeric(Object option) {
        String type = stringCall(option, "getType");
        return (type.isEmpty() || type.equalsIgnoreCase("INTEGER") || type.equalsIgnoreCase("DOUBLE"))
                && find(option, "shouldUseSlider") != null
                && (find(option, "getIntegerValue") != null || find(option, "getDoubleValue") != null);
    }

    static boolean isColor(Object option) {
        return stringCall(option, "getType").equalsIgnoreCase("COLOR")
                && find(option, "getIntegerValue") != null && find(option, "getDefaultIntegerValue") != null;
    }

    static boolean usesSlider(Object option) {
        try {
            return Boolean.TRUE.equals(find(option, "shouldUseSlider").invoke(option));
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    static void toggleSlider(Object option) { invokeNoArg(option, "toggleUseSlider"); }

    static double number(Object option, String method) {
        try {
            Method getter = find(option, method);
            return getter == null ? 0 : ((Number) getter.invoke(option)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) { return 0; }
    }

    static boolean integerOption(Object option) { return find(option, "getIntegerValue") != null; }

    static void setNumber(Object option, double value, boolean integer) {
        try {
            Method method = option.getClass().getMethod(integer ? "setIntegerValue" : "setDoubleValue",
                    integer ? int.class : double.class);
            method.trySetAccessible();
            if (integer) method.invoke(option, (int) Math.round(value));
            else method.invoke(option, value);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not set numeric MaLiLib option", exception);
        }
    }

    static String optionListDisplayName(Object option) {
        Object entry = optionListEntry(option);
        return entry == null ? value(option) : stringCall(entry, "getDisplayName", "getStringValue");
    }

    static Component optionListHover(Object option) {
        Object entry = optionListEntry(option);
        var result = Component.empty();
        if (entry == null) return result;
        try {
            Method getter = find(entry, "getHoverText");
            if (getter != null && getter.invoke(entry) instanceof Iterable<?> lines) {
                for (Object line : lines) {
                    if (line == null) continue;
                    if (!result.getString().isEmpty()) result.append("\n");
                    result.append(line instanceof Component text ? text.copy() : Component.literal(line.toString()));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return result;
    }

    static void cycleOptionList(Object option, boolean forward) {
        Object entry = optionListEntry(option);
        if (entry == null) return;
        try {
            Method cycle = entry.getClass().getMethod("cycle", boolean.class);
            cycle.trySetAccessible();
            Method setter = setter(option, "setOptionListValue");
            if (setter != null) setter.invoke(option, cycle.invoke(entry, forward));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not cycle dynamic MaLiLib option", exception);
        }
    }

    private static Object optionListEntry(Object option) {
        try {
            Method getter = find(option, "getOptionListValue");
            return getter == null ? null : getter.invoke(option);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static String value(Object option) {
        if (hasSetter(option, "setValueFromString")) {
            try {
                Method getter = find(option, "getStringValue");
                if (getter != null) {
                    Object result = getter.invoke(option);
                    if (result != null) return String.valueOf(result);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        try {
            Method getter = find(option, "getAsJsonElement");
            if (getter == null) return "";
            Object json = getter.invoke(option);
            return String.valueOf(json);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    static boolean editable(Object option) {
        return hasSetter(option, "setValueFromString") || hasSetter(option, "setValueFromJsonElement");
    }

    static boolean canReset(Object option) {
        return find(option, "resetToDefault") != null && find(option, "isModified") != null;
    }

    static boolean isModified(Object option) {
        try {
            Method getter = find(option, "isModified");
            return getter != null && Boolean.TRUE.equals(getter.invoke(option));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    static boolean resetToDefault(Object option) {
        return invokeNoArg(option, "resetToDefault");
    }

    static boolean isBoolean(Object option) {
        return find(option, "getBooleanValue") != null;
    }

    static boolean hasHotkey(Object option) {
        return find(option, "getKeybind") != null;
    }

    static Object keybind(Object option) {
        Method getter = find(option, "getKeybind");
        if (getter == null) return null;
        try {
            return getter.invoke(option);
        } catch (ReflectiveOperationException exception) {
            SparkFixClient.LOGGER.debug("Could not read dynamic MaLiLib keybind", exception);
            return null;
        }
    }

    static boolean booleanValue(Object option) {
        try {
            Method getter = find(option, "getBooleanValue");
            return getter != null && Boolean.TRUE.equals(getter.invoke(option));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static void setValue(Object option, String value) {
        Method stringSetter = setter(option, "setValueFromString");
        if (stringSetter != null) {
            try {
                stringSetter.invoke(option, value);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                SparkFixClient.LOGGER.debug("Could not set dynamic MaLiLib option", exception);
            }
            return;
        }

        Method jsonSetter = setter(option, "setValueFromJsonElement");
        if (jsonSetter == null) return;
        try {
            Class<?> parser = Class.forName("com.google.gson.JsonParser");
            Object json = parser.getMethod("parseString", String.class).invoke(null, value);
            jsonSetter.invoke(option, json);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JSON text editors produce invalid intermediate values while the user types.
        }
    }

    private static Method find(Object target, String name) {
        return ACCESSORS.get(target.getClass()).getters().get(name);
    }

    private static Method setter(Object target, String name) {
        return ACCESSORS.get(target.getClass()).setters().get(name);
    }

    private static boolean hasSetter(Object target, String name) {
        return setter(target, name) != null;
    }

    private static boolean invokeNoArg(Object target, String name) {
        if (target == null) return false;
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            method.invoke(target);
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not invoke {} on MaLiLib config handler", name, exception);
            return false;
        }
    }

    private static String stringCall(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = find(target, name);
                if (method == null) continue;
                Object result = method.invoke(target);
                if (result != null && !String.valueOf(result).isBlank()) return String.valueOf(result);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return "";
    }

    record DiscoveredGroup(String id, Object handler, List<Object> options) { }
    record DiscoveredModule(String id, String name, ModContainer container, List<String> groupIds) { }
}
