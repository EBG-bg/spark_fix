package dev.codex.spark_fix;

import net.minecraft.world.level.block.Blocks;

/**
 * Fixed, unmodified vanilla desert-pyramid trap. Natural badlands terracotta
 * cannot satisfy this evidence, regardless of the adjustable similarity.
 * Coordinates come from Minecraft 26.2 DesertPyramidPiece.postProcess.
 */
final class StructureFinderDesertPyramid {
    private StructureFinderDesertPyramid() {
    }

    /** Reads at most 28 immutable snapshot blocks; never accesses a live world. */
    static boolean matches(StructureFinder.BlockLookup blocks, int centerX, int tntY, int centerZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (blocks.get(centerX + dx, tntY, centerZ + dz) != Blocks.TNT) return false;
            }
        }
        if (blocks.get(centerX, tntY + 1, centerZ) != Blocks.CUT_SANDSTONE
                || blocks.get(centerX, tntY + 2, centerZ) != Blocks.STONE_PRESSURE_PLATE
                || blocks.get(centerX, tntY + 13, centerZ) != Blocks.DYED_TERRACOTTA.blue()) return false;
        for (int offset = -2; offset <= 2; offset += 4) {
            if (blocks.get(centerX + offset, tntY + 2, centerZ) != Blocks.CHEST
                    || blocks.get(centerX, tntY + 2, centerZ + offset) != Blocks.CHEST) return false;
        }
        // The four corners avoid the chest niches on each cardinal wall.
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                if (blocks.get(centerX + dx, tntY + 3, centerZ + dz) != Blocks.CHISELED_SANDSTONE
                        || blocks.get(centerX + dx, tntY + 4, centerZ + dz) != Blocks.CUT_SANDSTONE
                        || blocks.get(centerX + dx, tntY + 5, centerZ + dz) != Blocks.SANDSTONE) return false;
            }
        }
        return true;
    }
}
