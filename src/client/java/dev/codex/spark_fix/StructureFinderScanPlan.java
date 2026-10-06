package dev.codex.spark_fix;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses a useful order for scanning vertical chunk sections without hiding
 * any sections. The ranges are deliberately soft: every section is appended
 * after the preferred bands have had a turn.
 */
final class StructureFinderScanPlan {
    private static final List<String> TYPE_ORDER = List.of(
            "ancient_city", "bastion_remnant", "buried_treasure", "desert_pyramid",
            "end_city", "fortress", "fossil", "igloo", "jungle_pyramid", "mineshaft",
            "monument", "mansion", "monster_room", "nether_fossil", "pillager_outpost", "ruined_portal",
            "shipwreck", "stronghold", "sulfur_spring", "swamp_hut", "trail_ruins",
            "trial_chambers", "underwater_ruin", "village");

    private StructureFinderScanPlan() {
    }

    record Terrain(int minimumY, int sectionCount, int surfaceY, int floorY,
                   int seaLevel, int playerY, String dimension) {
        Terrain {
            sectionCount = Math.max(0, sectionCount);
            dimension = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT);
        }

        int maximumY() {
            return minimumY + Math.max(0, sectionCount * 16 - 1);
        }
    }

    private record Band(int low, int high, int preferred) {
        Band {
            if (low > high) {
                int swap = low;
                low = high;
                high = swap;
            }
            preferred = Math.clamp(preferred, low, high);
        }

        int distance(int y) {
            if (y < low) return low - y;
            if (y > high) return y - high;
            return 0;
        }

        int centerDistance(int y) {
            return Math.abs(y - preferred);
        }

        Band clamp(Terrain terrain) {
            int low = Math.max(terrain.minimumY(), this.low);
            int high = Math.min(terrain.maximumY(), this.high);
            if (low > high) return null;
            return new Band(low, high, Math.clamp(preferred, low, high));
        }
    }

    private record Lane(Band band, List<Integer> sections) {
    }

    static int[] sectionOrder(Set<String> types, Terrain terrain) {
        if (terrain.sectionCount() <= 0) return new int[0];

        List<Band> bands = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String type : TYPE_ORDER) {
            if (types == null || !types.contains(type)) continue;
            for (Band band : bandsFor(type, terrain)) {
                Band clamped = band.clamp(terrain);
                if (clamped == null) continue;
                String key = clamped.low + ":" + clamped.high + ":" + clamped.preferred;
                if (seen.add(key)) bands.add(clamped);
            }
        }
        if (bands.isEmpty()) {
            int player = Math.clamp(terrain.playerY(), terrain.minimumY(), terrain.maximumY());
            bands.add(new Band(player, player, player));
        }

        List<Lane> lanes = new ArrayList<>();
        for (Band band : bands) {
            List<Integer> sections = new ArrayList<>(terrain.sectionCount());
            for (int section = 0; section < terrain.sectionCount(); section++) sections.add(section);
            sections.sort(Comparator
                    .comparingInt((Integer section) -> band.distance(sectionY(terrain, section)))
                    .thenComparingInt(section -> band.centerDistance(sectionY(terrain, section)))
                    .thenComparingInt(Integer::intValue));
            lanes.add(new Lane(band, sections));
        }

        int[] order = new int[terrain.sectionCount()];
        BitSet used = new BitSet(terrain.sectionCount());
        int written = 0;
        int[] cursors = new int[lanes.size()];
        while (written < order.length) {
            boolean progressed = false;
            for (int laneIndex = 0; laneIndex < lanes.size(); laneIndex++) {
                Lane lane = lanes.get(laneIndex);
                while (cursors[laneIndex] < lane.sections.size()
                        && used.get(lane.sections.get(cursors[laneIndex]))) cursors[laneIndex]++;
                if (cursors[laneIndex] >= lane.sections.size()) continue;
                int section = lane.sections.get(cursors[laneIndex]++);
                if (used.get(section)) continue;
                used.set(section);
                order[written++] = section;
                progressed = true;
            }
            if (!progressed) break;
        }
        // Defensive fallback for future lane changes: no section may disappear.
        for (int section = 0; written < order.length; section++) {
            if (!used.get(section)) order[written++] = section;
        }
        return order;
    }

    static int markerPriority(String type, int y, Terrain terrain) {
        List<Band> bands = bandsFor(type, terrain);
        if (bands.isEmpty()) return Math.abs(y - terrain.playerY());
        int best = Integer.MAX_VALUE;
        for (Band band : bands) {
            Band clamped = band.clamp(terrain);
            if (clamped != null) best = Math.min(best, clamped.distance(y));
        }
        return best == Integer.MAX_VALUE ? Math.abs(y - terrain.playerY()) : best;
    }

    private static int sectionY(Terrain terrain, int section) {
        return terrain.minimumY() + section * 16 + 8;
    }

    private static List<Band> bandsFor(String type, Terrain terrain) {
        String dimension = terrain.dimension();
        boolean overworld = dimension.equals("overworld");
        boolean nether = dimension.equals("the_nether") || dimension.equals("nether");
        boolean end = dimension.equals("the_end") || dimension.equals("end");
        int surface = terrain.surfaceY();
        int floor = terrain.floorY();
        int min = terrain.minimumY();
        int sea = terrain.seaLevel();
        int player = terrain.playerY();
        List<Band> bands = switch (type) {
            case "village", "pillager_outpost", "mansion", "desert_pyramid", "jungle_pyramid",
                    "swamp_hut", "end_city" -> List.of(new Band(surface - 16, surface + 48, surface));
            case "igloo" -> List.of(new Band(surface - 16, surface + 48, surface),
                    new Band(surface - 48, surface, surface - 24));
            case "trail_ruins" -> List.of(new Band(surface - 48, surface + 16, surface - 24));
            case "buried_treasure" -> List.of(new Band(floor - 16, floor + 8, floor));
            case "shipwreck" -> List.of(new Band(floor - 16, floor + 32, floor),
                    new Band(surface - 8, surface + 8, surface));
            case "underwater_ruin" -> List.of(new Band(floor - 16, floor + 32, floor));
            case "monument" -> List.of(new Band(39, 62, 50), new Band(floor, floor + 32, floor + 16));
            case "ancient_city" -> List.of(new Band(-48, 16, -27));
            case "trial_chambers" -> List.of(new Band(-64, 16, -30));
            case "stronghold" -> List.of(new Band(min, Math.min(sea - 10, surface - 16), sea - 32));
            case "monster_room" -> List.of(new Band(min + 6, -1, -24),
                    new Band(0, Math.max(0, surface), 20));
            case "mineshaft" -> List.of(new Band(min, Math.min(sea - 10, surface - 16), sea - 32),
                    new Band(surface - 16, surface + 24, surface));
            case "fossil" -> List.of(new Band(floor - 32, floor - 8, floor - 20), new Band(min, -8, -24));
            case "bastion_remnant" -> List.of(new Band(16, 96, 33));
            case "fortress" -> List.of(new Band(32, 96, 64));
            case "nether_fossil" -> List.of(new Band(32, 127, 64));
            case "ruined_portal" -> nether
                    ? List.of(new Band(32, 112, 64))
                    : List.of(new Band(surface - 16, surface + 32, surface),
                    new Band(floor - 16, floor + 32, floor), new Band(min, -8, -24));
            case "sulfur_spring" -> List.of(new Band(player - 16, player + 16, player));
            default -> List.of(new Band(player - 16, player + 16, player));
        };
        if (dimension.isBlank() || (!overworld && !nether && !end)) {
            return List.of(new Band(player - 16, player + 16, player));
        }
        if (type.equals("end_city") && !end) return List.of(new Band(player - 16, player + 16, player));
        if ((type.equals("bastion_remnant") || type.equals("fortress") || type.equals("nether_fossil")) && !nether) {
            return List.of(new Band(player - 16, player + 16, player));
        }
        if (type.equals("ruined_portal") && !overworld && !nether) {
            return List.of(new Band(player - 16, player + 16, player));
        }
        return bands;
    }
}
