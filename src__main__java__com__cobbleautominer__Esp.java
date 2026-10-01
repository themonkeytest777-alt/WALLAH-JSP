package com.cobbleautominer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/** Contours de tous les minerais detectes, visibles a travers les murs et a tres longue distance. */
public final class Esp {
    private Esp() {}

    public static void render(WorldRenderContext ctx) {
        CamConfig cfg = CamConfig.I;
        if (!cfg.esp) return;
        List<OreScanner.Hit> list = Miner.I.scanner.esp;
        if (list.isEmpty()) return;
        MatrixStack m = ctx.matrixStack();
        if (m == null) return;
        Vec3d cam = ctx.camera().getPos();
        m.push();
        m.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = m.peek().getPositionMatrix();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2.0f);
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        BlockPos cur = Miner.I.target;
        for (OreScanner.Hit h : list) {
            int c = h.pos().equals(cur) ? 0xFFFFFF : h.color();
            box(b, mat, h.pos(), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
        }
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        m.pop();
    }

    private static void box(BufferBuilder b, Matrix4f m, BlockPos p, float r, float g, float bl) {
        float x0 = p.getX() - 0.002f, y0 = p.getY() - 0.002f, z0 = p.getZ() - 0.002f;
        float x1 = p.getX() + 1.002f, y1 = p.getY() + 1.002f, z1 = p.getZ() + 1.002f;
        line(b, m, x0, y0, z0, x1, y0, z0, r, g, bl); line(b, m, x1, y0, z0, x1, y0, z1, r, g, bl);
        line(b, m, x1, y0, z1, x0, y0, z1, r, g, bl); line(b, m, x0, y0, z1, x0, y0, z0, r, g, bl);
        line(b, m, x0, y1, z0, x1, y1, z0, r, g, bl); line(b, m, x1, y1, z0, x1, y1, z1, r, g, bl);
        line(b, m, x1, y1, z1, x0, y1, z1, r, g, bl); line(b, m, x0, y1, z1, x0, y1, z0, r, g, bl);
        line(b, m, x0, y0, z0, x0, y1, z0, r, g, bl); line(b, m, x1, y0, z0, x1, y1, z0, r, g, bl);
        line(b, m, x1, y0, z1, x1, y1, z1, r, g, bl); line(b, m, x0, y0, z1, x0, y1, z1, r, g, bl);
    }

    private static void line(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float bl) {
        b.vertex(m, x1, y1, z1).color(r, g, bl, 0.95f);
        b.vertex(m, x2, y2, z2).color(r, g, bl, 0.95f);
    }
}
