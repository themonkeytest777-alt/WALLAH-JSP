package com.cobbleautominer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.function.Predicate;

public final class OreScanner {
    public record Hit(BlockPos pos, int color) {}

    private final Map<Long, long[]> hits = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private boolean fullRescan = true;
    private int seenVersion = -1;
    public volatile List<Hit> esp = List.of();
    public int total;

    public void requestFullRescan() { fullRescan = true; }

    public void clear() {
        hits.clear(); queue.clear(); queued.clear();
        esp = List.of(); total = 0; fullRescan = true;
    }

    public void enqueue(int cx, int cz) {
        long k = ChunkPos.toLong(cx, cz);
        if (queued.add(k)) queue.add(k);
    }

    public void forget(int cx, int cz) { hits.remove(ChunkPos.toLong(cx, cz)); }

    public void tick(MinecraftClient mc, CamConfig cfg, long ticks) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        if (w == null || p == null) return;
        if (seenVersion != cfg.targetVersion) {
            seenVersion = cfg.targetVersion;
            hits.clear(); queue.clear(); queued.clear();
            fullRescan = true;
        }
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        if (fullRescan) {
            fullRescan = false;
            int r = Math.min(80, (int) Math.ceil(cfg.scanRadius / 16.0) + 1);
            List<int[]> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (dx * dx + dz * dz <= r * r && w.getChunkManager().getWorldChunk(pcx + dx, pcz + dz) != null)
                        list.add(new int[]{dx, dz});
            list.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
            for (int[] a : list) enqueue(pcx + a[0], pcz + a[1]);
        } else if (ticks % Math.max(10, cfg.rescanTicks) == 0) {
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) enqueue(pcx + dx, pcz + dz);
        }
        int budget = Math.max(1, cfg.chunksPerTick);
        while (budget-- > 0 && !queue.isEmpty()) {
            long k = queue.poll();
            queued.remove(k);
            scan(w, cfg, k);
        }
        if (ticks % 10 == 0) refreshEsp(w, p, cfg);
    }

    private void scan(ClientWorld w, CamConfig cfg, long key) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
        if (ch == null) { hits.remove(key); return; }
        Set<Block> set = cfg.targetSet;
        if (set.isEmpty()) { hits.remove(key); return; }
        Predicate<BlockState> pred = s -> set.contains(s.getBlock());
        ChunkSection[] secs = ch.getSectionArray();
        int base = ch.getBottomSectionCoord();
        long[] buf = new long[16];
        int n = 0;
        for (int i = 0; i < secs.length; i++) {
            ChunkSection sec = secs[i];
            if (sec == null || !sec.hasAny(pred)) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!pred.test(sec.getBlockState(x, y, z))) continue;
                        if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
                        buf[n++] = new BlockPos(cx * 16 + x, (base + i) * 16 + y, cz * 16 + z).asLong();
                    }
        }
        if (n == 0) hits.remove(key); else hits.put(key, Arrays.copyOf(buf, n));
    }

    /** Bloc cible le plus "rentable" (distance + penalite verticale). */
    public BlockPos pickNearest(ClientWorld w, CamConfig cfg, Vec3d from, Predicate<BlockPos> skip) {
        double best = Double.MAX_VALUE;
        BlockPos bp = null;
        double r2 = (double) cfg.scanRadius * cfg.scanRadius;
        for (long[] arr : new ArrayList<>(hits.values())) {
            for (long l : arr) {
                BlockPos pos = BlockPos.fromLong(l);
                double dx = pos.getX() + 0.5 - from.x, dz = pos.getZ() + 0.5 - from.z, dy = pos.getY() + 0.5 - from.y;
                double h2 = dx * dx + dz * dz;
                if (h2 > r2) continue;
                double cost = Math.sqrt(h2) + cfg.verticalWeight * Math.abs(dy);
                if (cost >= best || skip.test(pos)) continue;
                BlockState s = WorldUtil.state(w, pos);
                if (s == null) continue;
                if (!cfg.isTarget(s.getBlock())) { enqueue(pos.getX() >> 4, pos.getZ() >> 4); continue; }
                best = cost;
                bp = pos;
            }
        }
        return bp;
    }

    private static int colorOf(String path) {
        if (path.contains("dawn")) return 0xFFE066;
        if (path.contains("dusk")) return 0xB266FF;
        if (path.contains("shiny")) return 0xFF66C4;
        if (path.contains("ice")) return 0x66E0FF;
        return 0x66FF66;
    }

    private void refreshEsp(ClientWorld w, ClientPlayerEntity p, CamConfig cfg) {
        int t = 0;
        List<BlockPos> list = new ArrayList<>();
        double r2 = (double) cfg.espRange * cfg.espRange;
        for (long[] arr : hits.values()) {
            t += arr.length;
            if (!cfg.esp) continue;
            for (long l : arr) {
                BlockPos pos = BlockPos.fromLong(l);
                if (p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= r2) list.add(pos);
            }
        }
        total = t;
        if (!cfg.esp) { esp = List.of(); return; }
        list.sort(Comparator.comparingDouble(b -> p.squaredDistanceTo(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)));
        List<Hit> out = new ArrayList<>();
        for (BlockPos b : list) {
            if (out.size() >= cfg.espMaxBoxes) break;
            BlockState s = WorldUtil.state(w, b);
            if (s == null || !cfg.isTarget(s.getBlock())) continue;
            out.add(new Hit(b, colorOf(Registries.BLOCK.getId(s.getBlock()).getPath())));
        }
        esp = out;
    }
}
