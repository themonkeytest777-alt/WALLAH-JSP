package com.cobbleautominer;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** A* 3D avec creusage : marche, saut, chute, escalier creuse, creusage vertical. */
public final class PathFinder {
    public record Goal(Vec3d c, double radius, boolean eye) {}

    public static final class Step {
        public final BlockPos pos;
        public final BlockPos[] clear;
        public final boolean vertical, ascend;
        final double cost;

        Step(BlockPos pos, double cost, boolean vertical, boolean ascend, BlockPos... clear) {
            this.pos = pos; this.cost = cost; this.vertical = vertical; this.ascend = ascend; this.clear = clear;
        }
    }

    public static final class Result {
        public final List<Step> steps;
        public final boolean reached;
        public final BlockPos start;

        Result(List<Step> steps, boolean reached, BlockPos start) {
            this.steps = steps; this.reached = reached; this.start = start;
        }
    }

    private static final class Node {
        final BlockPos pos; final Node parent; final Step step; final double g; double f; boolean closed;

        Node(BlockPos pos, Node parent, Step step, double g) { this.pos = pos; this.parent = parent; this.step = step; this.g = g; }
    }

    private static final double WALK = 4.0, ASCEND = 7.0, FALL = 1.5, HW = 4.5, INF = 1e9;

    private ClientWorld w;
    private CamConfig cfg;
    private Tools tools;
    private ClientPlayerEntity p;
    private final Map<Long, Double> digMemo = new HashMap<>();

    private double dig(BlockPos q) {
        Double m = digMemo.get(q.asLong());
        if (m != null) return m;
        double r;
        BlockState s = WorldUtil.state(w, q);
        if (s == null) r = INF;
        else if (WorldUtil.passable(w, q)) r = 0;
        else if (!WorldUtil.diggable(w, cfg, q)) r = INF;
        else r = (tools.breakTicks(p, w, q, s) + 1.0) * cfg.digCostMultiplier;
        digMemo.put(q.asLong(), r);
        return r;
    }

    private boolean safe(BlockPos spot) { return !cfg.avoidLava || !WorldUtil.nearLava(w, spot); }

    private void expand(BlockPos a, List<Step> out) {
        BlockPos a2 = a.up(2);
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos c = a.offset(d), c1 = c.up(), c2 = c.up(2);
            // montee d'une marche
            if (WorldUtil.solidFloor(w, c)) {
                double x = dig(c1) + dig(c2) + dig(a2);
                if (x < INF && safe(c1)) out.add(new Step(c1, ASCEND + x, false, true, a2, c1, c2));
            }
            double pass = dig(c) + dig(c1);
            if (pass >= INF) continue;
            BlockPos cd = c.down();
            if (WorldUtil.solidFloor(w, cd)) {
                if (safe(c)) out.add(new Step(c, WALK + pass, false, false, c, c1));
                if (cfg.allowDigDown && WorldUtil.solidFloor(w, cd.down())) {
                    double dd = dig(cd);
                    if (dd < INF && safe(cd)) out.add(new Step(cd, WALK + pass + dd + FALL, false, false, c, c1, cd));
                }
            } else if (WorldUtil.passable(w, cd)) {
                for (int k = 1; k <= cfg.maxFallDistance; k++) {
                    BlockPos l = c.down(k);
                    if (!WorldUtil.passable(w, l)) break;
                    if (WorldUtil.solidFloor(w, l.down())) {
                        if (safe(l)) out.add(new Step(l, WALK + pass + FALL * k, false, false, c, c1));
                        break;
                    }
                }
            }
        }
        // creusage vertical
        if (cfg.allowDigDown) {
            BlockPos l = a.down();
            if (WorldUtil.solidFloor(w, l) && WorldUtil.solidFloor(w, l.down())) {
                double dd = dig(l);
                if (dd < INF && safe(l)) out.add(new Step(l, FALL + dd, true, false, l));
            }
        }
    }

    private static Vec3d center(BlockPos n, Goal g) {
        return g.eye() ? new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5)
                : new Vec3d(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5);
    }

    private static double h(BlockPos n, Goal g) {
        return Math.max(0, center(n, g).distanceTo(g.c()) - g.radius()) * HW;
    }

    public Result find(ClientWorld w, CamConfig cfg, Tools tools, ClientPlayerEntity p, BlockPos start, Goal goal) {
        this.w = w; this.cfg = cfg; this.tools = tools; this.p = p;
        digMemo.clear();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> all = new HashMap<>();
        Node s = new Node(start, null, null, 0);
        s.f = h(start, goal);
        all.put(start.asLong(), s);
        open.add(s);
        Node best = s;
        double bestH = s.f;
        int nodes = 0;
        long deadline = System.nanoTime() + cfg.pathBudgetMs * 1_000_000L;
        List<Step> edges = new ArrayList<>();
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.closed) continue;
            n.closed = true;
            if (center(n.pos, goal).distanceTo(goal.c()) <= goal.radius()) return build(n, true, start);
            double hn = h(n.pos, goal);
            if (hn < bestH) { bestH = hn; best = n; }
            if (++nodes > cfg.pathMaxNodes || ((nodes & 63) == 0 && System.nanoTime() > deadline)) break;
            edges.clear();
            expand(n.pos, edges);
            for (Step e : edges) {
                long k = e.pos.asLong();
                double g = n.g + e.cost;
                Node ex = all.get(k);
                if (ex != null && (ex.closed || g >= ex.g)) continue;
                if (ex != null) ex.closed = true;
                Node nn = new Node(e.pos, n, e, g);
                nn.f = g + h(e.pos, goal);
                all.put(k, nn);
                open.add(nn);
            }
        }
        if (best == s) return null;
        return build(best, false, start);
    }

    private Result build(Node end, boolean reached, BlockPos start) {
        LinkedList<Step> steps = new LinkedList<>();
        for (Node n = end; n != null && n.step != null; n = n.parent) steps.addFirst(n.step);
        return new Result(new ArrayList<>(steps), reached, start);
    }
}
