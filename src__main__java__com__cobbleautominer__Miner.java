package com.cobbleautominer;

import com.cobbleautominer.PathFinder.Goal;
import com.cobbleautominer.PathFinder.Step;
import com.cobbleautominer.mixin.InteractionManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cerveau de l'auto-mineur : choix de cible, deplacement, minage, ramassage. */
public final class Miner {
    public static final Miner I = new Miner();

    private enum Mode { MINE, COLLECT }

    public final OreScanner scanner = new OreScanner();
    private final Tools tools = new Tools();
    private final PathFinder finder = new PathFinder();

    public boolean enabled;
    public String status = "inactif";
    public BlockPos target;
    public int mined;
    public long ticks;

    private Mode mode = Mode.MINE;
    private PathFinder.Result path;
    private int pathIdx, replans, targetAge, stuckTicks, stuckFails, breakTicks, idleTicks;
    private boolean needPath = true;
    private BlockPos breakingPos, lastMined;
    private long collectStart;
    private ItemEntity collectItem;
    private Vec3d lastPos = Vec3d.ZERO;
    private Vec3d anchor;
    private int anchorIdx, freeTicks;
    private long anchorTick;
    private boolean cullForced;
    private final Map<Long, Long> blacklist = new HashMap<>();

    // ------------------------------------------------------------ API
    public void toggle() {
        if (enabled) stop("desactive"); else start();
    }

    private void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        enabled = true;
        mined = 0;
        idleTicks = 0;
        mode = Mode.MINE;
        resetTargetState();
        target = null;
        blacklist.clear();
        scanner.requestFullRescan();
        msg("Auto-mineur ACTIVE");
    }

    public void stop(String why) {
        boolean was = enabled;
        enabled = false;
        releaseKeys();
        target = null;
        collectItem = null;
        resetTargetState();
        status = why;
        if (was) msg("Auto-mineur arrete : " + why);
    }

    public void onDisconnect() {
        enabled = false;
        target = null;
        status = "inactif";
        scanner.clear();
        blacklist.clear();
    }

    private void msg(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal("[CAM] " + s), true);
    }

    // ------------------------------------------------------------ tick
    public void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        CamConfig cfg = CamConfig.I;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) { enabled = false; return; }
        ticks++;
        tools.tick(ticks);

        if (cfg.noCulling) { mc.chunkCullingEnabled = false; cullForced = true; }
        else if (cullForced) { mc.chunkCullingEnabled = true; cullForced = false; }

        if (enabled || cfg.esp) scanner.tick(mc, cfg, ticks);
        if (!enabled || mc.interactionManager == null) return;

        if (mc.currentScreen != null && cfg.pauseInScreens) {
            releaseKeys();
            status = "pause (menu ouvert)";
            return;
        }
        if (p.isSpectator()) { stop("mode spectateur"); return; }
        if (!safe(p, cfg)) return;
        if (ticks % 200 == 0) blacklist.values().removeIf(t -> t < ticks);
        if (cfg.removeBreakCooldown) ((InteractionManagerAccessor) mc.interactionManager).cam$setCooldown(0);

        if (mode == Mode.COLLECT) { collect(p, w, cfg); return; }

        if (target == null) {
            target = scanner.pickNearest(w, cfg, p.getPos(), b -> blacklist.getOrDefault(b.asLong(), 0L) > ticks);
            resetTargetState();
            if (target == null) {
                releaseKeys();
                status = "aucun minerai dans le rayon (" + scanner.total + " connus)";
                if (cfg.stopWhenNone && ++idleTicks > 100) stop("plus de minerai");
                return;
            }
            idleTicks = 0;
        }
        targetAge++;
        BlockState ts = WorldUtil.state(w, target);
        if (ts == null || !cfg.isTarget(ts.getBlock())) { onMined(cfg); return; }
        if (targetAge > cfg.targetTimeoutTicks) { giveUp("delai depasse", 1200); return; }
        mineOrTravel(p, w, cfg);
    }

    private boolean safe(ClientPlayerEntity p, CamConfig cfg) {
        if (p.getHealth() < cfg.minHealth) { stop("vie trop basse"); return false; }
        if (cfg.stopOnDamage && p.hurtTime > 0) { stop("degats recus"); return false; }
        if (!p.isCreative() && p.getHungerManager().getFoodLevel() < cfg.minFood) { stop("faim trop basse"); return false; }
        if (cfg.stopWhenInventoryFull && p.getInventory().getEmptySlot() == -1) { stop("inventaire plein"); return false; }
        if (p.isInLava() || p.isOnFire()) { stop("danger : feu / lave"); return false; }
        return true;
    }

    // ------------------------------------------------------------ cible
    private void resetTargetState() {
        path = null; needPath = true; pathIdx = 0;
        replans = 0; targetAge = 0; stuckTicks = 0; stuckFails = 0; breakTicks = 0;
        breakingPos = null;
        anchor = null; anchorIdx = 0; freeTicks = 0;
    }

    private void giveUp(String why, int banTicks) {
        if (target != null) blacklist.put(target.asLong(), ticks + banTicks);
        status = "cible abandonnee : " + why;
        target = null;
        resetTargetState();
    }

    private void onMined(CamConfig cfg) {
        boolean ours = breakingPos != null && breakingPos.equals(target);
        if (ours) { mined++; lastMined = target; }
        target = null;
        resetTargetState();
        if (ours && cfg.collectDrops) {
            mode = Mode.COLLECT;
            collectStart = ticks;
            collectItem = null;
        }
    }

    private void mineOrTravel(ClientPlayerEntity p, ClientWorld w, CamConfig cfg) {
        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(target);
        if (eye.distanceTo(c) <= cfg.reach) {
            BlockPos obs = findObstruction(p, w, cfg, eye, c);
            if (obs == null) { giveUp("obstacle dangereux", 400); return; }
            mineBlock(p, w, cfg, obs);
            return;
        }
        if (!travel(p, w, cfg, new Goal(c, Math.max(1.5, cfg.reach - 0.7), true))) giveUp("aucun chemin", 900);
    }

    /** Premier bloc a casser entre l'oeil et la cible (la cible elle-meme si la voie est libre). null = dangereux. */
    private BlockPos findObstruction(ClientPlayerEntity p, ClientWorld w, CamConfig cfg, Vec3d eye, Vec3d c) {
        BlockHitResult hit = w.raycast(new RaycastContext(eye, c, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return target;
        BlockPos hp = hit.getBlockPos();
        if (hp.equals(target)) return target;
        if (!WorldUtil.diggable(w, cfg, hp)) return null;
        if (hp.equals(p.getBlockPos().down()) && !WorldUtil.solidFloor(w, hp.down())) return null;
        return hp;
    }

    // ------------------------------------------------------------ minage
    private boolean mineBlock(ClientPlayerEntity p, ClientWorld w, CamConfig cfg, BlockPos bp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockState s = WorldUtil.state(w, bp);
        if (s == null || s.isAir()) return true;
        int slot = cfg.autoTool ? tools.bestSlot(p, s, cfg) : p.getInventory().selectedSlot;
        if (slot < 0) {
            releaseMove();
            giveUp("outil manquant / use", 1200);
            return false;
        }
        if (slot != p.getInventory().selectedSlot) p.getInventory().selectedSlot = slot;

        releaseMove();
        anchor = null;
        Vec3d eye = p.getEyePos();
        Vec3d aim = Vec3d.ofCenter(bp);
        status = "minage : " + s.getBlock().getName().getString();
        if (!rotateToPoint(p, eye, aim, cfg)) return true;

        if (breakingPos == null || !breakingPos.equals(bp)) { breakingPos = bp; breakTicks = 0; }
        Direction face = Direction.getFacing(eye.x - aim.x, eye.y - aim.y, eye.z - aim.z);
        if (mc.interactionManager.updateBlockBreakingProgress(bp, face)) p.swingHand(Hand.MAIN_HAND);
        if (++breakTicks > cfg.maxBreakTicks) { giveUp("minage trop long", 1200); return false; }
        return true;
    }

    // ------------------------------------------------------------ deplacement
    private boolean travel(ClientPlayerEntity p, ClientWorld w, CamConfig cfg, Goal goal) {
        if (!p.isOnGround()) return true;
        if (freeTicks > 0) {
            freeTicks--;
            breakFree(p, w, cfg, goal.c());
            if (freeTicks == 0) needPath = true;
            return true;
        }
        if (needPath || path == null) {
            if (++replans > 15) return false;
            path = finder.find(w, cfg, tools, p, p.getBlockPos(), goal);
            pathIdx = 0;
            needPath = false;
            stuckTicks = 0;
            if (path == null || path.steps.isEmpty()) { path = null; return false; }
        }
        follow(p, w, cfg);
        return true;
    }

    private boolean straight(List<Step> st, PathFinder.Result r, int j) {
        if (j + 1 >= st.size()) return false;
        BlockPos prev = j == 0 ? r.start : st.get(j - 1).pos;
        BlockPos cur = st.get(j).pos, nxt = st.get(j + 1).pos;
        return cur.getX() - prev.getX() == nxt.getX() - cur.getX()
                && cur.getZ() - prev.getZ() == nxt.getZ() - cur.getZ()
                && cur.getY() == prev.getY() && nxt.getY() == cur.getY();
    }

    private void follow(ClientPlayerEntity p, ClientWorld w, CamConfig cfg) {
        List<Step> st = path.steps;
        BlockPos feet = p.getBlockPos();
        for (int j = Math.max(0, pathIdx - 1); j < Math.min(st.size(), pathIdx + 6); j++) {
            if (!feet.equals(st.get(j).pos)) continue;
            Vec3d tc = Vec3d.ofBottomCenter(st.get(j).pos);
            double hd = Math.hypot(tc.x - p.getX(), tc.z - p.getZ());
            if (j == st.size() - 1 || straight(st, path, j) || hd < 0.5) pathIdx = Math.max(pathIdx, j + 1);
        }
        if (pathIdx >= st.size()) { path = null; needPath = true; releaseMove(); return; }

        Step cur = st.get(pathIdx);
        Vec3d eye = p.getEyePos();
        int end = Math.min(st.size(), pathIdx + Math.max(1, cfg.tunnelLookahead));
        outer:
        for (int j = pathIdx; j < end; j++) {
            Step s = st.get(j);
            for (BlockPos q : s.clear) {
                if (WorldUtil.state(w, q) == null) { path = null; needPath = true; return; }
                if (WorldUtil.passable(w, q)) continue;
                if (!WorldUtil.diggable(w, cfg, q)) { path = null; needPath = true; return; }
                if (q.equals(feet.down()) && !(s.vertical && j == pathIdx)) continue;
                if (Vec3d.ofCenter(q).distanceTo(eye) > cfg.reach - 0.4) break outer;
                mineBlock(p, w, cfg, q);
                return;
            }
        }
        walk(p, cfg, cur, feet);
    }

    private void walk(ClientPlayerEntity p, CamConfig cfg, Step cur, BlockPos feet) {
        Vec3d tc = Vec3d.ofBottomCenter(cur.pos);
        double dx = tc.x - p.getX(), dz = tc.z - p.getZ();
        double hd = Math.hypot(dx, dz);
        float yawErr = 0f;
        // Pas de rotation quand on est quasi au centre : atan2 sur un vecteur minuscule fait tourner la camera.
        if (hd > 0.2) {
            float yawT = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
            rotate(p, yawT, 12f, cfg);
            yawErr = Math.abs(MathHelper.wrapDegrees(yawT - p.getYaw()));
        }
        boolean needUp = cur.pos.getY() > feet.getY();
        boolean go = (hd > 0.1 || cur.pos.getY() != feet.getY()) && yawErr < 40f;
        // On ne saute QUE s'il faut monter (sinon on rebondit autour du bloc a l'infini).
        boolean jump = needUp && p.isOnGround() && hd < 1.3;
        boolean sprint = cfg.sprint && go && yawErr < 12f && !cur.ascend && hd > 0.9
                && p.getHungerManager().getFoodLevel() > 6;
        GameOptions o = MinecraftClient.getInstance().options;
        o.forwardKey.setPressed(go);
        o.jumpKey.setPressed(jump);
        o.sprintKey.setPressed(sprint);
        status = "deplacement vers " + (target != null ? target.toShortString() : "?");

        // Detection de blocage basee sur la PROGRESSION horizontale (sauter sur place ne compte pas).
        if (anchor == null || pathIdx != anchorIdx
                || Math.hypot(p.getX() - anchor.x, p.getZ() - anchor.z) > 0.6) {
            anchor = p.getPos();
            anchorIdx = pathIdx;
            anchorTick = ticks;
        } else if (ticks - anchorTick > cfg.maxStuckTicks) {
            onStuck();
        }
    }

    private void onStuck() {
        anchor = null;
        stuckFails++;
        path = null;
        needPath = true;
        releaseMove();
        if (stuckFails > 4) { giveUp("bloque", 1200); return; }
        freeTicks = 25;
        status = "bloque : deblocage";
    }

    /** Se libere : casse plafond / devant / dessous vers la cible, sinon avance en sautant. */
    private void breakFree(ClientPlayerEntity p, ClientWorld w, CamConfig cfg, Vec3d goalPt) {
        BlockPos feet = p.getBlockPos();
        Vec3d eye = p.getEyePos();
        double gx = goalPt.x - p.getX(), gz = goalPt.z - p.getZ();
        Direction d = Direction.getFacing(gx, 0.0, gz);
        List<BlockPos> cand = new ArrayList<>();
        cand.add(feet.up().offset(d));
        cand.add(feet.offset(d));
        cand.add(feet.up(2));
        if (goalPt.y > p.getY() + 1.0) cand.add(feet.up(2).offset(d));
        if (goalPt.y < p.getY() - 1.5) cand.add(feet.down());
        for (BlockPos q : cand) {
            if (WorldUtil.state(w, q) == null || WorldUtil.passable(w, q)) continue;
            if (!WorldUtil.diggable(w, cfg, q)) continue;
            if (Vec3d.ofCenter(q).distanceTo(eye) > cfg.reach - 0.4) continue;
            mineBlock(p, w, cfg, q);
            return;
        }
        // rien a casser : on force le passage
        float yawT = (float) (MathHelper.atan2(gz, gx) * 180.0 / Math.PI) - 90f;
        if (Math.hypot(gx, gz) > 0.3) rotate(p, yawT, 12f, cfg);
        GameOptions o = MinecraftClient.getInstance().options;
        o.forwardKey.setPressed(true);
        o.jumpKey.setPressed(p.isOnGround() && p.horizontalCollision);
        o.sprintKey.setPressed(false);
        anchor = null;
    }

    // ------------------------------------------------------------ ramassage
    private void finishCollect() {
        mode = Mode.MINE;
        collectItem = null;
        resetTargetState();
        target = null;
    }

    private void collect(ClientPlayerEntity p, ClientWorld w, CamConfig cfg) {
        long age = ticks - collectStart;
        if (collectItem == null || collectItem.isRemoved()) {
            collectItem = null;
            if (lastMined != null) {
                double best = Double.MAX_VALUE;
                for (ItemEntity e : w.getEntitiesByClass(ItemEntity.class,
                        Box.of(Vec3d.ofCenter(lastMined), 12, 12, 12), e -> !e.isRemoved())) {
                    double d = e.squaredDistanceTo(p);
                    if (d < best && d <= cfg.collectMaxDistance * cfg.collectMaxDistance) { best = d; collectItem = e; }
                }
            }
            if (collectItem == null) {
                releaseMove();
                status = "attente du drop...";
                if (age > 8) finishCollect();
                return;
            }
            path = null; needPath = true; replans = 0; stuckFails = 0;
        }
        if (age > cfg.collectTimeoutTicks) { finishCollect(); return; }
        if (p.squaredDistanceTo(collectItem) < 1.5) { releaseMove(); status = "ramassage"; return; }
        status = "ramassage du drop";
        if (!travel(p, w, cfg, new Goal(collectItem.getPos(), 1.1, false))) finishCollect();
    }

    // ------------------------------------------------------------ rotation / touches
    private boolean rotateToPoint(ClientPlayerEntity p, Vec3d eye, Vec3d pt, CamConfig cfg) {
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) (-(MathHelper.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI));
        return rotate(p, yaw, pitch, cfg);
    }

    private boolean rotate(ClientPlayerEntity p, float yawT, float pitchT, CamConfig cfg) {
        float dy = MathHelper.wrapDegrees(yawT - p.getYaw());
        float dp = pitchT - p.getPitch();
        float sy = step(dy, cfg), sp = step(dp, cfg);
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
        return Math.abs(dy - sy) <= cfg.aimTolerance && Math.abs(dp - sp) <= cfg.aimTolerance;
    }

    private float step(float d, CamConfig cfg) {
        if (cfg.instantAim) return d;
        float a = Math.abs(d);
        float s = Math.min(a, Math.max(Math.min(a, 4f), Math.min(a * (float) cfg.rotationEase, (float) cfg.rotationSpeed)));
        return Math.copySign(s, d);
    }

    private void releaseMove() {
        GameOptions o = MinecraftClient.getInstance().options;
        o.forwardKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sprintKey.setPressed(false);
    }

    private void releaseKeys() {
        releaseMove();
        MinecraftClient.getInstance().options.sneakKey.setPressed(false);
    }
}
