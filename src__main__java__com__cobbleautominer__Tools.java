package com.cobbleautominer;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public final class Tools {
    private final Map<BlockState, Double> cache = new HashMap<>();
    private long stamp;

    public void tick(long ticks) {
        if (ticks - stamp > 60) { cache.clear(); stamp = ticks; }
    }

    /** Meilleur slot de la barre rapide pour ce bloc, -1 si aucun outil adapte. */
    public int bestSlot(ClientPlayerEntity p, BlockState s, CamConfig cfg) {
        PlayerInventory inv = p.getInventory();
        boolean req = s.isToolRequired();
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= cfg.minToolDurability) continue;
            if (req && !st.isSuitableFor(s)) continue;
            double sp = st.isEmpty() ? 1.0 : st.getMiningSpeedMultiplier(s);
            if (sp > bestScore + 1e-6 || (Math.abs(sp - bestScore) < 1e-6 && i == inv.selectedSlot)) {
                best = i;
                bestScore = sp;
            }
        }
        return best;
    }

    /** Estimation du temps de minage (ticks) avec le meilleur outil. */
    public double breakTicks(ClientPlayerEntity p, ClientWorld w, BlockPos pos, BlockState s) {
        Double c = cache.get(s);
        if (c != null) return c;
        float h = s.getHardness(w, pos);
        double res;
        if (h <= 0) res = 1;
        else {
            int slot = bestSlot(p, s, CamConfig.I);
            double speed = 1;
            boolean can = !s.isToolRequired();
            if (slot >= 0) {
                ItemStack st = p.getInventory().getStack(slot);
                speed = st.isEmpty() ? 1.0 : Math.max(1.0, st.getMiningSpeedMultiplier(s));
                can = !s.isToolRequired() || st.isSuitableFor(s);
            }
            double delta = speed / h / (can ? 30.0 : 100.0);
            res = Math.min(800, Math.max(1, Math.ceil(1.0 / delta)));
        }
        cache.put(s, res);
        return res;
    }
}
