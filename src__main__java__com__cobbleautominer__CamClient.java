package com.cobbleautominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class CamClient implements ClientModInitializer {
    public static KeyBinding toggleKey, guiKey, espKey, refreshKey;

    @Override
    public void onInitializeClient() {
        CamConfig.load();
        String cat = "key.categories.cam2";
        // "." du pave numerique pour activer/desactiver
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.cam2.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_DECIMAL, cat));
        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.cam2.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_0, cat));
        espKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.cam2.esp", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_MULTIPLY, cat));
        refreshKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.cam2.refresh", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_DIVIDE, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.wasPressed()) Miner.I.toggle();
            while (guiKey.wasPressed()) mc.setScreen(new MinerScreen());
            while (espKey.wasPressed()) {
                CamConfig.I.esp = !CamConfig.I.esp;
                CamConfig.save();
                if (mc.player != null) mc.player.sendMessage(Text.literal("[CAM] ESP " + (CamConfig.I.esp ? "active" : "desactive")), true);
            }
            while (refreshKey.wasPressed()) refreshChunks();
            Miner.I.tick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            CamConfig.I.recompile();
            Miner.I.scanner.clear();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Miner.I.onDisconnect());
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> Miner.I.scanner.enqueue(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> Miner.I.scanner.forget(chunk.getPos().x, chunk.getPos().z));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(Esp::render);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            CamConfig cfg = CamConfig.I;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!cfg.hud || mc.options.hudHidden || mc.player == null) return;
            Miner m = Miner.I;
            int y = 4;
            ctx.drawTextWithShadow(mc.textRenderer, "Auto-mineur : " + (m.enabled ? "ACTIF" : "inactif"), 4, y, m.enabled ? 0x55FF55 : 0xAAAAAA);
            if (m.enabled || m.mined > 0) {
                y += 10;
                ctx.drawTextWithShadow(mc.textRenderer, "Mines : " + m.mined + "  |  detectes : " + m.scanner.total, 4, y, 0xFFFFFF);
                y += 10;
                ctx.drawTextWithShadow(mc.textRenderer, m.status, 4, y, 0xFFD966);
                if (m.target != null) {
                    y += 10;
                    ctx.drawTextWithShadow(mc.textRenderer, "Cible : " + m.target.toShortString() + " (" + (int) Math.sqrt(mc.player.squaredDistanceTo(m.target.getX() + .5, m.target.getY() + .5, m.target.getZ() + .5)) + " m)", 4, y, 0x99DDFF);
                }
            }
        });
    }

    public static void refreshChunks() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.worldRenderer != null) mc.worldRenderer.reload();
        Miner.I.scanner.requestFullRescan();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[CAM] Chunks recharges"), true);
    }
}
