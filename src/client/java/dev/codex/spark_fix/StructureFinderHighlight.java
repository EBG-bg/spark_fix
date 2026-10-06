package dev.codex.spark_fix;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders only the finder results already extracted from received client chunks. */
final class StructureFinderHighlight {
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int MAX_VISIBLE_PIECES = 512;
    private static final int MAX_VISIBLE_LABELS = 12;
    private static final double LABEL_DISTANCE_SQUARED = 96.0 * 96.0;
    private static final RenderType THROUGH_TERRAIN_LINES = RenderType.create(
            "spark_fix_structure_finder_known_structure_lines",
            RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                            .withLocation(Identifier.fromNamespaceAndPath("spark_fix", "pipeline/structure_finder_lines"))
                            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                            .build()))
                    .setOutputTarget(OutputTarget.MAIN_TARGET)
                    .createRenderSetup());
    private static final VoxelShape MARKER = Shapes.box(-0.04, -0.04, -0.04, 1.04, 1.04, 1.04);
    private static final RenderStateDataKey<FrameHighlights> HIGHLIGHTS =
            RenderStateDataKey.create(() -> "spark_fix:structure_finder_highlights");
    private static List<StructureFinderResults.HighlightGroup> cachedGroups = List.of();
    private static List<Piece> cachedPieces = List.of();
    private static Map<Piece, Highlight> geometryCache = Map.of();
    private static boolean registered;

    private StructureFinderHighlight() {}

    static void register() {
        if (registered) return;
        registered = true;
        LevelExtractionEvents.END_EXTRACTION.register(StructureFinderHighlight::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(StructureFinderHighlight::submit);
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> {
            cachedGroups = List.of();
            cachedPieces = List.of();
            geometryCache = Map.of();
        });
    }

    private static void extract(LevelExtractionContext context) {
        List<StructureFinderResults.HighlightGroup> groups = StructureFinder.highlightGroups();
        if (groups != cachedGroups) {
            List<Piece> pieces = new ArrayList<>();
            for (var group : groups) {
                for (var detection : group.pieces()) pieces.add(new Piece(group.id(), detection));
            }
            cachedPieces = List.copyOf(pieces);
            cachedGroups = groups;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        List<NearbyHighlight> nearby = new ArrayList<>();
        for (Piece piece : cachedPieces) {
            AABB bounds = piece.detection().bounds();
            double distanceSquared = bounds.distanceToSqr(camera);
            if (distanceSquared > MAX_DISTANCE_SQUARED) continue;
            var frustum = context.levelState().cameraRenderState.cullFrustum;
            if (frustum != null && !frustum.isVisible(bounds)) continue;
            nearby.add(new NearbyHighlight(piece, distanceSquared));
        }
        nearby.sort(Comparator.comparingDouble(NearbyHighlight::distanceSquared));
        List<Highlight> visible = new ArrayList<>();
        List<Highlight> labels = new ArrayList<>();
        Set<Long> labelled = new HashSet<>();
        Map<Piece, Highlight> nextGeometry = new HashMap<>();
        for (NearbyHighlight candidate : nearby) {
            if (visible.size() == MAX_VISIBLE_PIECES) break;
            Piece piece = candidate.piece();
            Highlight highlight = geometryCache.get(piece);
            if (highlight == null) highlight = createHighlight(piece);
            visible.add(highlight);
            nextGeometry.put(piece, highlight);
            if (labels.size() < MAX_VISIBLE_LABELS && candidate.distanceSquared() <= LABEL_DISTANCE_SQUARED
                    && labelled.add(piece.groupId())) labels.add(highlight);
        }
        // Bound cached voxel geometry too: far-away remembered pieces cost no
        // shapes and cannot force a huge rebuild whenever another match arrives.
        geometryCache = nextGeometry;
        ((FabricRenderState) context.levelState()).setData(HIGHLIGHTS,
                new FrameHighlights(List.copyOf(visible), List.copyOf(labels)));
    }

    private static Highlight createHighlight(Piece piece) {
        var detection = piece.detection();
        BlockPos pos = detection.pos();
        AABB bounds = detection.bounds();
        VoxelShape outline = Shapes.create(bounds.move(-pos.getX(), -pos.getY(), -pos.getZ()).inflate(0.02));
        Component label = Component.translatable("structure_finder.structure." + detection.type())
                .append(Component.literal(" #" + piece.groupId() + "  "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        return new Highlight(pos.getX(), pos.getY(), pos.getZ(), bounds, outline,
                typeColor(detection.type()), label);
    }

    static int typeColor(String type) {
        return switch (type) {
            case "buried_treasure" -> 0xFFD166;
            case "monster_room" -> 0xAC8CFF;
            case "trial_chambers" -> 0x62E3BF;
            case "mineshaft" -> 0xFFA96B;
            case "shipwreck" -> 0x74BFFF;
            case "ancient_city" -> 0x74E0E8;
            case "village" -> 0xA6E878;
            case "ruined_portal" -> 0xEE8AD9;
            default -> 0x87B1F9;
        };
    }

    private static void submit(LevelRenderContext context) {
        FrameHighlights frame = ((FabricRenderState) context.levelState())
                .getDataOrDefault(HIGHLIGHTS, new FrameHighlights(List.of(), List.of()));
        List<Highlight> highlights = frame.pieces();
        if (highlights.isEmpty()) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poses = context.poseStack();
        if (poses == null) return;
        for (Highlight highlight : highlights) {
            poses.pushPose();
            try {
                poses.translate(highlight.x() - camera.x, highlight.y() - camera.y,
                        highlight.z() - camera.z);
                // Keep visible surfaces crisp; the faint pass also locates already matched buried pieces.
                context.submitNodeCollector().submitShapeOutline(poses, highlight.outline(),
                        RenderTypes.lines(), 0xD0000000 | highlight.color(), 1.5F, false);
                context.submitNodeCollector().submitShapeOutline(poses, MARKER,
                        RenderTypes.lines(), 0xF0000000 | highlight.color(), 2.0F, false);
                // The final boolean schedules this after translucent terrain; it is not a depth-test flag.
                context.submitNodeCollector().submitShapeOutline(poses, highlight.outline(),
                        THROUGH_TERRAIN_LINES, 0x68000000 | highlight.color(), 1.25F, true);
                context.submitNodeCollector().submitShapeOutline(poses, MARKER,
                        THROUGH_TERRAIN_LINES, 0xB0000000 | highlight.color(), 1.75F, true);
            } finally {
                poses.popPose();
            }
        }
        for (Highlight label : frame.labels()) {
            poses.pushPose();
            try {
                poses.translate(label.x() + 0.5 - camera.x, label.y() + 1.6 - camera.y,
                        label.z() + 0.5 - camera.z);
                poses.mulPose(context.levelState().cameraRenderState.orientation);
                poses.scale(0.025F, -0.025F, 0.025F);
                context.submitNodeCollector().submitText(poses,
                        -Minecraft.getInstance().font.width(label.label()) / 2.0F, 0,
                        label.label().getVisualOrderText(), false, Font.DisplayMode.SEE_THROUGH,
                        15728880, 0xFF000000 | label.color(), 0x60000000, 0);
            } finally {
                poses.popPose();
            }
        }
    }

    private record Piece(long groupId, StructureFinder.Detection detection) {}
    private record Highlight(int x, int y, int z, AABB bounds, VoxelShape outline, int color, Component label) {}
    private record NearbyHighlight(Piece piece, double distanceSquared) {}
    private record FrameHighlights(List<Highlight> pieces, List<Highlight> labels) {}
}
