package dev.codex.spark_fix;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Optional, reflection-only Xaero bridge for clickable local structure waypoints. */
public final class StructureFinderXaeroBridge {
    private static final String MINIMAP = "xaerominimap";
    private static final String COMMAND = "structure_waypoint";
    private static final String DELETE_COMMAND = "structure_waypoint_delete";
    private static final String DISMISS_COMMAND = "structure_highlight_dismiss";
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static boolean registered;

    private StructureFinderXaeroBridge() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("spark_fix")
                        .then(ClientCommands.literal(COMMAND)
                                .requires(source -> isInstalled())
                                .then(ClientCommands.argument("token", StringArgumentType.word())
                                        .executes(context -> addWaypoint(context.getSource(),
                                                StringArgumentType.getString(context, "token")))))
                        .then(ClientCommands.literal(DELETE_COMMAND)
                                .requires(source -> isInstalled())
                                .then(ClientCommands.argument("token", StringArgumentType.word())
                                        .executes(context -> deleteWaypoint(context.getSource(),
                                                StringArgumentType.getString(context, "token")))))
                        .then(ClientCommands.literal(DISMISS_COMMAND)
                                .then(ClientCommands.argument("token", StringArgumentType.word())
                                        .executes(context -> dismissHighlight(context.getSource(),
                                                StringArgumentType.getString(context, "token")))))));
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> clear());
    }

    public static Component coordinateLink(StructureFinder.Detection detection) {
        BlockPos pos = detection.pos();
        Component coordinates = Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        String token = UUID.randomUUID().toString().replace("-", "");
        ClientLevel level = Minecraft.getInstance().level;
        ResourceKey<?> dimension = level == null ? null : level.dimension();
        synchronized (StructureFinderXaeroBridge.class) {
            PENDING.put(token, new Pending(detection, level, dimension, null));
        }
        register();
        var result = Component.empty();
        if (isInstalled()) {
            Style style = Style.EMPTY.withColor(0x55AAFF)
                    .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + COMMAND + " " + token));
            Component delete = Component.translatable("chat.spark_fix.structure_finder.delete_waypoint")
                    .withStyle(Style.EMPTY.withColor(0xFF8585)
                            .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + DELETE_COMMAND + " " + token)));
            result.append(coordinates.copy().withStyle(style)).append(Component.literal(" ")).append(delete);
        } else {
            result.append(coordinates);
        }
        Component dismiss = Component.translatable("chat.spark_fix.structure_finder.dismiss_highlight")
                .withStyle(Style.EMPTY.withColor(0xFFD166)
                        .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + DISMISS_COMMAND + " " + token)));
        return result.append(Component.literal(" ")).append(dismiss);
    }

    public static synchronized void clear() {
        PENDING.clear();
    }

    private static int addWaypoint(FabricClientCommandSource source, String token) {
        Pending pending;
        synchronized (StructureFinderXaeroBridge.class) {
            pending = PENDING.get(token);
        }
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return 0;
        try {
            Object session = currentMinimapSession();
            if (session == null) return 0;
            Object world = currentXaeroWorld(session, pending.dimension);
            if (world == null) return 0;
            Object set = invoke(world, "getCurrentWaypointSet");
            if (set == null) return 0;
            BlockPos pos = pending.detection.pos();
            for (Object existing : (Iterable<?>) invoke(set, "getWaypoints")) {
                if (matchesWaypoint(existing, pending.name(), pos.getX(), pos.getY(), pos.getZ())) {
                    rememberWaypointSet(token, pending, set);
                    notify(client, "chat.spark_fix.structure_finder.waypoint_exists", pending, pos);
                    return 1;
                }
            }
            Class<?> color = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            @SuppressWarnings({"unchecked", "rawtypes"}) Object blue = Enum.valueOf((Class<? extends Enum>) color, "LIGHT_BLUE");
            Class<?> waypoint = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> constructor = waypoint.getConstructor(int.class, int.class, int.class,
                    String.class, String.class, color);
            Object created = constructor.newInstance(pos.getX(), pos.getY(), pos.getZ(), pending.name(), "SF", blue);
            Object io = invoke(session, "getWorldManagerIO");
            addAndSave(world, set, created, io);
            rememberWaypointSet(token, pending, set);
            notify(client, "chat.spark_fix.structure_finder.waypoint_added", pending, pos);
            return 1;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not add Structure Finder Xaero waypoint", exception);
            if (pending != null && sameWorld(client.level, pending)) {
                notify(client, "chat.spark_fix.structure_finder.waypoint_failed", pending,
                        pending.detection.pos());
            }
            return 0;
        }
    }

    private static int deleteWaypoint(FabricClientCommandSource source, String token) {
        Pending pending;
        synchronized (StructureFinderXaeroBridge.class) {
            pending = PENDING.get(token);
        }
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return 0;
        try {
            Object session = currentMinimapSession();
            if (session == null) return 0;
            Object world = currentXaeroWorld(session, pending.dimension);
            if (world == null) return 0;
            BlockPos pos = pending.detection.pos();
            if (!deleteAndSave(world, pending.waypointSet, pending.name(), pos.getX(), pos.getY(),
                    pos.getZ(), invoke(session, "getWorldManagerIO"))) {
                notify(client, "chat.spark_fix.structure_finder.waypoint_not_found", pending, pos);
                return 0;
            }
            notify(client, "chat.spark_fix.structure_finder.waypoint_deleted", pending, pos);
            return 1;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not delete Structure Finder Xaero waypoint", exception);
            notify(client, "chat.spark_fix.structure_finder.waypoint_delete_failed", pending,
                    pending.detection.pos());
            return 0;
        }
    }

    static void addAndSave(Object world, Object set, Object waypoint, Object io)
            throws ReflectiveOperationException {
        changeAndSave(world, set, "add", waypoint, io);
    }

    static boolean deleteAndSave(Object world, Object preferredSet, String name, int x, int y, int z, Object io)
            throws ReflectiveOperationException {
        List<Object> sets = new ArrayList<>();
        for (Object set : (Iterable<?>) invoke(world, "getIterableWaypointSets")) sets.add(set);
        if (sets.remove(preferredSet)) sets.addFirst(preferredSet);
        for (Object set : sets) {
            for (Object waypoint : (Iterable<?>) invoke(set, "getWaypoints")) {
                if (!matchesWaypoint(waypoint, name, x, y, z)) continue;
                changeAndSave(world, set, "remove", waypoint, io);
                return true;
            }
        }
        return false;
    }

    private static void changeAndSave(Object world, Object set, String operation, Object waypoint, Object io)
            throws ReflectiveOperationException {
        List<Object> before = new ArrayList<>();
        for (Object existing : (Iterable<?>) invoke(set, "getWaypoints")) before.add(existing);
        try {
            invoke(set, operation, waypoint);
            invoke(io, "saveWorld", world);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                invoke(set, "clear");
                invoke(set, "addAll", before);
            } catch (ReflectiveOperationException | RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    private static boolean matchesWaypoint(Object waypoint, String name, int x, int y, int z)
            throws ReflectiveOperationException {
        if (intValue(waypoint, "getX") != x || intValue(waypoint, "getY") != y
                || intValue(waypoint, "getZ") != z || !name.equals(stringValue(waypoint, "getName"))
                || !XaeroStructureWaypointCleaner.INITIALS.equalsIgnoreCase(stringValue(waypoint, "getSymbol"))
                || Boolean.TRUE.equals(invoke(waypoint, "isThirdParty"))) return false;
        Object purpose = invoke(waypoint, "getPurpose");
        return purpose == null || !Boolean.TRUE.equals(invoke(purpose, "isDeath"));
    }

    private static synchronized void rememberWaypointSet(String token, Pending pending, Object set) {
        if (PENDING.get(token) == pending) {
            PENDING.put(token, new Pending(pending.detection, pending.level, pending.dimension, set));
        }
    }

    private static int dismissHighlight(FabricClientCommandSource source, String token) {
        Pending pending;
        synchronized (StructureFinderXaeroBridge.class) {
            pending = PENDING.get(token);
        }
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return 0;
        boolean dismissed = StructureFinder.dismissHighlight(pending.detection);
        notify(client, dismissed
                        ? "chat.spark_fix.structure_finder.highlight_dismissed"
                        : "chat.spark_fix.structure_finder.highlight_not_found",
                pending, pending.detection.pos());
        return dismissed ? 1 : 0;
    }

    private static Object currentMinimapSession() throws ReflectiveOperationException {
        Object hudSession = invokeStatic("xaero.common.XaeroMinimapSession", "getCurrentSession");
        if (hudSession == null) return null;
        Object modMain = invoke(hudSession, "getModMain");
        Object moduleManager = invoke(invoke(modMain, "getHud"), "getModuleManager");
        Object module = invoke(moduleManager, "get", Identifier.fromNamespaceAndPath(MINIMAP, "minimap"));
        return module == null ? null : invoke(hudSession, "getSession", module);
    }

    private static Object currentXaeroWorld(Object session, ResourceKey<?> dimension)
            throws ReflectiveOperationException {
        Object world = invoke(invoke(session, "getWorldManager"), "getCurrentWorld");
        return world == null || !sameDimension(world, dimension) ? null : world;
    }

    private static void notify(Minecraft client, String key, Pending pending, BlockPos pos) {
        if (client.gui != null) {
            client.gui.chatListener().handleSystemMessage(Component.translatable(key,
                    pending.name(), pos.getX(), pos.getY(), pos.getZ()), false);
        }
    }

    private static boolean sameWorld(ClientLevel level, Pending pending) {
        return level != null && level == pending.level && level.dimension().equals(pending.dimension);
    }

    private static boolean sameDimension(Object world, ResourceKey<?> dimension) {
        if (dimension == null) return false;
        try { return dimension.equals(invoke(world, "getDimId")); }
        catch (ReflectiveOperationException exception) { return false; }
    }

    private static boolean isInstalled() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(MINIMAP);
    }

    private static Object invokeStatic(String className, String method) throws ReflectiveOperationException {
        return Class.forName(className).getMethod(method).invoke(null);
    }

    private static Object invoke(Object target, String method, Object... args) throws ReflectiveOperationException {
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != args.length) continue;
            try { return candidate.invoke(target, args); } catch (IllegalArgumentException ignored) { }
        }
        throw new NoSuchMethodException(method);
    }

    private static int intValue(Object target, String method) throws ReflectiveOperationException {
        return ((Number) invoke(target, method)).intValue();
    }

    private static String stringValue(Object target, String method) throws ReflectiveOperationException {
        return String.valueOf(invoke(target, method));
    }

    private record Pending(StructureFinder.Detection detection, ClientLevel level,
                           ResourceKey<?> dimension, Object waypointSet) {
        private String name() {
            return StructureFinderCatalog.entries().stream().filter(entry -> entry.id().equals(detection.type()))
                    .findFirst().map(StructureFinderCatalog.Entry::zhName).orElse(detection.type());
        }
    }
}
