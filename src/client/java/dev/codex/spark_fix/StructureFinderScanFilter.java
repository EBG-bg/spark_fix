package dev.codex.spark_fix;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;

/** Builds compact discovery positions without filtering blocks needed by template comparisons. */
final class StructureFinderScanFilter {
    final Object selectionIdentity;
    private final Map<Block, Boolean> triggers;
    private final boolean columnOnly;

    /** True marks discovery blocks restricted to the treasure's chunk-local X/Z=9 column. */
    StructureFinderScanFilter(Object selectionIdentity, Map<Block, Boolean> triggers) {
        this.selectionIdentity = selectionIdentity;
        this.triggers = new IdentityHashMap<>(triggers);
        columnOnly = !triggers.isEmpty() && triggers.values().stream().allMatch(Boolean::booleanValue);
    }

    boolean canTrigger(Block block, int x, int z) {
        Boolean restricted = triggers.get(block);
        return restricted != null && (!restricted || (x & 15) == 9 && (z & 15) == 9);
    }

    Builder builder(PalettedContainer<BlockState> states) {
        return new Builder(states);
    }

    boolean maybeHas(PalettedContainer<BlockState> states) {
        return states != null && states.maybeHas(state -> triggers.containsKey(state.getBlock()));
    }

    static final class Positions {
        private final BitSet positions;

        private Positions(BitSet positions) {
            this.positions = positions;
        }

        int next(int start) {
            int next = positions.nextSetBit(start);
            return next < 0 ? 4096 : next;
        }
    }

    final class Builder {
        private final PalettedContainer<BlockState> states;
        private final BitSet positions = new BitSet();
        private int offset;
        private final int limit;
        int reads;

        private Builder(PalettedContainer<BlockState> states) {
            this.states = states;
            limit = states == null || !states.maybeHas(state -> triggers.containsKey(state.getBlock()))
                    ? 0 : columnOnly ? 16 : 4096;
        }

        boolean step(int budget) {
            int end = Math.min(limit, offset + Math.max(0, budget));
            while (offset < end) {
                int position = columnOnly ? (offset << 8) | (9 << 4) | 9 : offset;
                offset++;
                int x = position & 15, y = position >> 8, z = position >> 4 & 15;
                reads++;
                if (canTrigger(states.get(x, y, z).getBlock(), x, z)) positions.set(position);
            }
            return offset == limit;
        }

        Positions finish() {
            if (offset != limit) throw new IllegalStateException("Discovery positions are incomplete");
            return new Positions(positions);
        }
    }
}
