package com.cobbleautominer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;

public final class WorldUtil {
    private WorldUtil() {}

    /** null = chunk non charge / hors du monde. */
    public static BlockState state(ClientWorld w, BlockPos p) {
        if (p.getY() < w.getBottomY() || p.getY() >= w.getTopY()) return null;
        WorldChunk c = w.getChunkManager().getWorldChunk(p.getX() >> 4, p.getZ() >> 4);
        return c == null ? null : c.getBlockState(p);
    }

    private static boolean danger(BlockState s) {
        return s.isIn(BlockTags.FIRE) || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.WITHER_ROSE);
    }

    public static boolean passable(ClientWorld w, BlockPos p) {
        BlockState s = state(w, p);
        if (s == null) return false;
        return s.getCollisionShape(w, p).isEmpty() && s.getFluidState().isEmpty() && !danger(s);
    }

    public static boolean solidFloor(ClientWorld w, BlockPos p) {
        BlockState s = state(w, p);
        if (s == null || !s.getFluidState().isEmpty()) return false;
        if (s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.CAMPFIRE)
                || s.isOf(Blocks.SOUL_CAMPFIRE) || s.isOf(Blocks.HONEY_BLOCK)) return false;
        return s.isSideSolidFullSquare(w, p, Direction.UP);
    }

    public static boolean touches(ClientWorld w, BlockPos p, TagKey<Fluid> tag) {
        BlockState s = state(w, p);
        if (s != null && s.getFluidState().isIn(tag)) return true;
        for (Direction d : Direction.values()) {
            BlockState n = state(w, p.offset(d));
            if (n != null && n.getFluidState().isIn(tag)) return true;
        }
        return false;
    }

    public static boolean nearLava(ClientWorld w, BlockPos p) {
        return touches(w, p, FluidTags.LAVA) || touches(w, p.up(), FluidTags.LAVA);
    }

    /** Peut-on casser ce bloc sans danger (lave, bedrock, blocs proteges...) ? */
    public static boolean diggable(ClientWorld w, CamConfig cfg, BlockPos p) {
        BlockState s = state(w, p);
        if (s == null) return false;
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.getHardness(w, p) < 0) return false;
        if (cfg.isProtected(s.getBlock()) && !cfg.isTarget(s.getBlock())) return false;
        if (cfg.avoidLava && touches(w, p, FluidTags.LAVA)) return false;
        if (cfg.avoidWater && touches(w, p, FluidTags.WATER)) return false;
        return true;
    }
}
