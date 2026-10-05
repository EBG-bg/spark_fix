package dev.codex.spark_fix;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Renders only the finder results already extracted from received client chunks. */
final class StructureFinderHighlight {
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int MAX_VISIBLE = 256;
    private static final int OUTLINE_COLOR = 0xD05BCAF5;
    private static final int MARKER_COLOR = 0xF087B1F9;
    private static final int OCCLUDED_OUTLINE_COLOR = 0x685BCAF5;
    private static final int OCCLUDED_MARKER_COLOR = 0xB087B1F9;
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
    private static final RenderStateDataKey<List<Highlight>> HIGHLIGHTS =
            RenderStateDataKey.create(() -> "spark_fix:structure_finder_highlights");
    private static List<StructureFinder.Detection> cachedDetections = List.of();
    private static List<Highlight> cachedHighlights = List.of();
    private static boolean registered;

    private StructureFinderHighlight() {}

    static void register() {
        if (registered) return;
        registered = true;
        LevelExtractionEvents.END_EXTRACTION.register(StructureFinderHighlight::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(StructureFinderHighlight::submit);
    }

    private static void extract(LevelExtractionContext context) {
        List<StructureFinder.Detection> detections = StructureFinder.detections();
        // Build voxel geometry only when the finder's immutable snapshot changes.
        if (detections != cachedDetections) {
            List<Highlight> highlights = new ArrayList<>(detections.size());
            for (StructureFinder.Detection detection : detections) {
                BlockPos pos = detection.pos();
                AABB bounds = detection.bounds();
                VoxelShape outline = Shapes.create(bounds.move(-pos.getX(), -pos.getY(), -pos.getZ())
                        .inflate(0.02));
                highlights.add(new Highlight(pos.getX(), pos.getY(), pos.getZ(), bounds, outline));
            }
            cachedHighlights = List.copyOf(highlights);
            cachedDetections = detections;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        List<NearbyHighlight> nearby = new ArrayList<>();
        for (Highlight highlight : cachedHighlights) {
            // Nearest-face distance keeps a nearby corner of a large piece visible.
            double distanceSquared = highlight.bounds().distanceToSqr(camera);
            if (distanceSquared > MAX_DISTANCE_SQUARED) continue;
            var frustum = context.levelState().cameraRenderState.cullFrustum;
            if (frustum != null && !frustum.isVisible(highlight.bounds())) continue;
            nearby.add(new NearbyHighlight(highlight, distanceSquared));
        }
        if (nearby.size() > MAX_VISIBLE) {
            nearby.sort(Comparator.comparingDouble(NearbyHighlight::distanceSquared));
        }
        List<Highlight> visible = nearby.stream().limit(MAX_VISIBLE)
                .map(NearbyHighlight::highlight).toList();
        ((FabricRenderState) context.levelState()).setData(HIGHLIGHTS, visible);
    }

    private static void submit(LevelRenderContext context) {
        List<Highlight> highlights = ((FabricRenderState) context.levelState())
                .getDataOrDefault(HIGHLIGHTS, List.of());
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
                        RenderTypes.lines(), OUTLINE_COLOR, 1.5F, false);
                context.submitNodeCollector().submitShapeOutline(poses, MARKER,
                        RenderTypes.lines(), MARKER_COLOR, 2.0F, false);
                // The final boolean schedules this after translucent terrain; it is not a depth-test flag.
                context.submitNodeCollector().submitShapeOutline(poses, highlight.outline(),
                        THROUGH_TERRAIN_LINES, OCCLUDED_OUTLINE_COLOR, 1.25F, true);
                context.submitNodeCollector().submitShapeOutline(poses, MARKER,
                        THROUGH_TERRAIN_LINES, OCCLUDED_MARKER_COLOR, 1.75F, true);
            } finally {
                poses.popPose();
            }
        }
    }

    private record Highlight(int x, int y, int z, AABB bounds, VoxelShape outline) {}
    private record NearbyHighlight(Highlight highlight, double distanceSquared) {}
}
