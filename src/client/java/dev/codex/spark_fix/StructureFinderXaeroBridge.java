package dev.codex.spark_fix;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Optional, reflection-only Xaero bridge for clickable local structure waypoints. */
public final class StructureFinderXaeroBridge {
    private static final String MINIMAP = "xaerominimap";
    private static final String COMMAND = "structure_waypoint";
    private static final String DELETE_COMMAND = "structure_waypoint_delete";
    private static final String DISMISS_COMMAND = "structure_highlight_dismiss";
    private static final String RESULTS_COMMAND = "structure_results";
    private static final int RESULTS_PER_PAGE = 8;
    private static final int MAX_PENDING_LINKS = 8192;
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>(32, 0.75F, true);
    private static final Map<LinkKey, String> LINK_TOKENS = new HashMap<>();
    private static boolean registered;

    private StructureFinderXaeroBridge() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("spark_fix")
                        .then(ClientCommands.literal(RESULTS_COMMAND)
                                .executes(context -> listNearbyResults(context.getSource(), 0))
                                .then(ClientCommands.argument("page", IntegerArgumentType.integer(0))
                                        .executes(context -> listNearbyResults(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")))))
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
        ClientLevel level = Minecraft.getInstance().level;
        ResourceKey<?> dimension = level == null ? null : level.dimension();
        String token = rememberLink(detection, level, dimension);
        register();
        var result = Component.empty();
        if (isInstalled()) {
            Component description = Component.translatable("structure_finder.structure." + detection.type())
                    .append(Component.literal("  " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            Style style = Style.EMPTY.withColor(0x55AAFF)
                    .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + COMMAND + " " + token))
                    .withHoverEvent(new HoverEvent.ShowText(description));
            Component add = Component.translatable("chat.spark_fix.structure_finder.add_waypoint").withStyle(style);
            Component delete = Component.translatable("chat.spark_fix.structure_finder.delete_waypoint")
                    .withStyle(Style.EMPTY.withColor(0xFF8585)
                            .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + DELETE_COMMAND + " " + token)));
            result.append(coordinates.copy().withStyle(style)).append(Component.literal(" "))
                    .append(add).append(Component.literal(" ")).append(delete);
        } else {
            result.append(coordinates);
        }
        Component dismiss = Component.translatable("chat.spark_fix.structure_finder.dismiss_highlight")
                .withStyle(Style.EMPTY.withColor(0xFFD166)
                        .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + DISMISS_COMMAND + " " + token)));
        return result.append(Component.literal(" ")).append(dismiss);
    }

    static Component nearbyResultsLink() {
        register();
        return Component.translatable("chat.spark_fix.structure_finder.nearby_results")
                .withStyle(Style.EMPTY.withColor(0x87B1F9)
                        .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + RESULTS_COMMAND)));
    }

    private static int listNearbyResults(FabricClientCommandSource source, int requestedPage) {
        Minecraft client = source.getClient();
        if (client.player == null || client.level == null) return 0;
        var position = client.player.position();
        List<NearbyResult> nearby = new ArrayList<>();
        for (var group : StructureFinder.highlightGroups()) {
            var closest = group.pieces().stream()
                    .min(Comparator.comparingDouble(piece -> piece.bounds().distanceToSqr(position)));
            if (closest.isEmpty()) continue;
            var nearestPiece = closest.get();
            double distance = nearestPiece.bounds().distanceToSqr(position);
            if (distance <= 256.0 * 256.0) nearby.add(new NearbyResult(group.id(),
                    groupWaypointTarget(group), distance));
        }
        nearby.sort(Comparator.comparingDouble(NearbyResult::distanceSquared));
        if (nearby.isEmpty()) {
            client.gui.chatListener().handleSystemMessage(
                    Component.translatable("chat.spark_fix.structure_finder.no_nearby_results"), false);
            return 1;
        }
        int pages = (nearby.size() + RESULTS_PER_PAGE - 1) / RESULTS_PER_PAGE;
        int page = Math.min(requestedPage, pages - 1);
        MutableComponent message = Component.translatable("chat.spark_fix.structure_finder.results_title")
                .append(Component.literal(" " + (page + 1) + "/" + pages));
        // Every group shares one waypoint target, even when the closest piece changes.
        for (NearbyResult result : nearby.subList(page * RESULTS_PER_PAGE,
                Math.min(nearby.size(), (page + 1) * RESULTS_PER_PAGE))) {
            var detection = result.detection();
            message.append(Component.literal("\n"));
            message.append(Component.translatable("structure_finder.structure." + detection.type())
                    .withColor(StructureFinderHighlight.typeColor(detection.type())));
            message.append(Component.literal(" #" + result.groupId() + "  "));
            message.append(coordinateLink(detection));
        }
        if (page > 0) message.append(Component.literal("\n"))
                .append(resultsPageLink("chat.spark_fix.structure_finder.previous_results", page - 1));
        if (page + 1 < pages) message.append(Component.literal(page > 0 ? " " : "\n"))
                .append(resultsPageLink("chat.spark_fix.structure_finder.next_results", page + 1));
        client.gui.chatListener().handleSystemMessage(message, false);
        return nearby.size();
    }

    private static Component resultsPageLink(String key, int page) {
        return Component.translatable(key).withStyle(Style.EMPTY.withColor(0x87B1F9)
                .withClickEvent(new ClickEvent.RunCommand("/spark_fix " + RESULTS_COMMAND + " " + page)));
    }

    private record NearbyResult(long groupId, StructureFinder.Detection detection, double distanceSquared) {}

    static StructureFinder.Detection groupWaypointTarget(StructureFinderResults.HighlightGroup group) {
        return group.detection();
    }

    /** Repeated paging keeps the same actions and original waypoint set alive. */
    static synchronized String rememberLink(StructureFinder.Detection detection, ClientLevel level,
                                            ResourceKey<?> dimension) {
        LinkKey key = new LinkKey(level, dimension, detection.type(), detection.pos());
        String token = LINK_TOKENS.get(key);
        Pending previous = token == null ? null : PENDING.get(token);
        if (previous == null) {
            token = UUID.randomUUID().toString().replace("-", "");
            LINK_TOKENS.put(key, token);
        }
        PENDING.put(token, new Pending(detection, level, dimension,
                previous == null ? null : previous.waypointSet));
        if (PENDING.size() > MAX_PENDING_LINKS) {
            var oldest = PENDING.entrySet().iterator();
            var expired = oldest.next();
            Pending removed = expired.getValue();
            LINK_TOKENS.remove(new LinkKey(removed.level, removed.dimension,
                    removed.detection.type(), removed.detection.pos()), expired.getKey());
            oldest.remove();
        }
        return token;
    }

    private static synchronized Pending pendingLink(String token) {
        return PENDING.get(token);
    }

    public static synchronized void clear() {
        PENDING.clear();
        LINK_TOKENS.clear();
    }

    private static int addWaypoint(FabricClientCommandSource source, String token) {
        Pending pending = pendingLink(token);
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return expiredLink(client);
        try {
            Object session = currentMinimapSession();
            Object world = session == null ? null : currentXaeroWorld(session, pending.dimension);
            Object set = world == null ? null : invoke(world, "getCurrentWaypointSet");
            if (set == null) {
                notify(client, "chat.spark_fix.structure_finder.waypoint_failed", pending, pending.detection.pos());
                return 0;
            }
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
        Pending pending = pendingLink(token);
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return expiredLink(client);
        try {
            Object session = currentMinimapSession();
            Object world = session == null ? null : currentXaeroWorld(session, pending.dimension);
            if (world == null) {
                notify(client, "chat.spark_fix.structure_finder.waypoint_delete_failed", pending,
                        pending.detection.pos());
                return 0;
            }
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
        Pending pending = pendingLink(token);
        Minecraft client = source.getClient();
        if (pending == null || !sameWorld(client.level, pending)) return expiredLink(client);
        boolean dismissed = StructureFinder.dismissHighlight(pending.detection);
        notify(client, dismissed
                        ? "chat.spark_fix.structure_finder.highlight_dismissed"
                        : "chat.spark_fix.structure_finder.highlight_not_found",
                pending, pending.detection.pos());
        return dismissed ? 1 : 0;
    }

    private static int expiredLink(Minecraft client) {
        if (client.gui != null) client.gui.chatListener().handleSystemMessage(
                Component.translatable("chat.spark_fix.structure_finder.link_expired"), false);
        return 0;
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
                    Component.literal(pending.name()), Component.literal(Integer.toString(pos.getX())),
                    Component.literal(Integer.toString(pos.getY())), Component.literal(Integer.toString(pos.getZ()))), false);
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

    private record LinkKey(ClientLevel level, ResourceKey<?> dimension, String type, BlockPos pos) {}

    private record Pending(StructureFinder.Detection detection, ClientLevel level,
                           ResourceKey<?> dimension, Object waypointSet) {
        private String name() {
            return StructureFinderCatalog.entries().stream().filter(entry -> entry.id().equals(detection.type()))
                    .findFirst().map(StructureFinderCatalog.Entry::zhName).orElse(detection.type());
        }
    }
}
