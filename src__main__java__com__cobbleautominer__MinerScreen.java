package com.cobbleautominer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.*;
import java.util.function.*;

public class MinerScreen extends Screen {
    private enum Tab {
        MINING("Minage"), MOVE("Mouvement"), SAFETY("Securite"), BLOCKS("Blocs"), VISUAL("Visuel / Chunks");
        final String title;
        Tab(String t) { title = t; }
    }

    private record Row(String label, ClickableWidget w) {}

    private static Tab lastTab = Tab.BLOCKS;
    private static final int ROW = 24, LROW = 18;

    private final List<Row> rows = new ArrayList<>();
    private final List<Block> listed = new ArrayList<>();
    private int left, panelW, top = 48, bottom, scroll, listScroll;
    private String search = "";
    private boolean showAll;

    public MinerScreen() { super(Text.literal("Cobble Auto Miner")); }

    private static CamConfig c() { return CamConfig.I; }

    // ---------------------------------------------------------------- widgets
    private static class Slide extends SliderWidget {
        private final String name, suffix;
        private final double min, max, step;
        private final DoubleConsumer set;

        Slide(String name, String suffix, double min, double max, double step, double cur, DoubleConsumer set) {
            super(0, 0, 150, 20, Text.empty(), (Math.min(max, Math.max(min, cur)) - min) / (max - min));
            this.name = name; this.suffix = suffix; this.min = min; this.max = max; this.step = step; this.set = set;
            updateMessage();
        }

        private double val() { return Math.round((min + value * (max - min)) / step) * step; }

        @Override protected void updateMessage() {
            double v = val();
            String s = step >= 1 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
            setMessage(Text.literal(s + suffix));
        }

        @Override protected void applyValue() { set.accept(val()); }
    }

    private void add(String label, ClickableWidget w, String tip) {
        if (tip != null) w.setTooltip(Tooltip.of(Text.literal(tip)));
        rows.add(new Row(label, w));
        addDrawableChild(w);
    }

    private static Text onOff(boolean b) { return Text.literal(b ? "OUI" : "NON"); }

    private void toggle(String label, String tip, BooleanSupplier get, Consumer<Boolean> set) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(onOff(get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            ref[0].setMessage(onOff(get.getAsBoolean()));
        }).dimensions(0, 0, 150, 20).build();
        add(label, ref[0], tip);
    }

    private void slider(String label, String tip, String suffix, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
        add(label, new Slide(label, suffix, min, max, step, get.getAsDouble(), set), tip);
    }

    private void text(String label, String tip, Supplier<List<String>> get, Consumer<List<String>> set) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, 0, 0, 150, 20, Text.literal(label));
        f.setMaxLength(4000);
        f.setText(String.join(", ", get.get()));
        f.setChangedListener(s -> {
            List<String> l = new ArrayList<>();
            for (String p : s.split(",")) if (!p.isBlank()) l.add(p.trim());
            set.accept(l);
        });
        add(label, f, tip);
    }

    private void action(String label, String tip, String btn, Runnable r) {
        add(label, ButtonWidget.builder(Text.literal(btn), b -> r.run()).dimensions(0, 0, 150, 20).build(), tip);
    }

    // ---------------------------------------------------------------- init
    @Override
    protected void init() {
        panelW = Math.min(width - 16, 470);
        left = (width - panelW) / 2;
        bottom = height - 34;
        rows.clear();

        Tab[] ts = Tab.values();
        int tw = panelW / ts.length;
        for (int i = 0; i < ts.length; i++) {
            Tab t = ts[i];
            ButtonWidget b = ButtonWidget.builder(Text.literal(t.title), x -> { lastTab = t; scroll = 0; listScroll = 0; clearAndInit(); })
                    .dimensions(left + i * tw, 22, tw - 2, 18).build();
            b.active = t != lastTab;
            addDrawableChild(b);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Termine"), b -> close()).dimensions(width / 2 - 154, height - 26, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Recharger chunks"), b -> CamClient.refreshChunks())
                .dimensions(width / 2 - 50, height - 26, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reinitialiser"), b -> { CamConfig.reset(); Miner.I.scanner.requestFullRescan(); clearAndInit(); })
                .dimensions(width / 2 + 54, height - 26, 100, 20).build());

        switch (lastTab) {
            case MINING -> buildMining();
            case MOVE -> buildMove();
            case SAFETY -> buildSafety();
            case BLOCKS -> buildBlocks();
            case VISUAL -> buildVisual();
        }
        layout();
    }

    private void buildMining() {
        toggle("Supprimer le delai entre blocs", "Enleve la pause de 5 ticks apres chaque bloc casse (minage en continu).", () -> c().removeBreakCooldown, v -> c().removeBreakCooldown = v);
        toggle("Outil automatique", "Choisit le meilleur outil de la barre rapide pour chaque bloc.", () -> c().autoTool, v -> c().autoTool = v);
        slider("Durabilite mini outil", "N'utilise plus un outil en dessous de cette durabilite restante.", "", 0, 200, 1, () -> c().minToolDurability, v -> c().minToolDurability = (int) v);
        slider("Portee de minage", "Distance max a laquelle on casse un bloc (vanilla = 4.5).", " blocs", 2.5, 4.5, 0.1, () -> c().reach, v -> c().reach = v);
        slider("Minage anticipe", "Nombre de pas de tunnel casses a l'avance avant de marcher.", " pas", 1, 6, 1, () -> c().tunnelLookahead, v -> c().tunnelLookahead = (int) v);
        slider("Delai max par bloc", "Abandonne un bloc si le minage depasse ce nombre de ticks.", " ticks", 40, 1200, 20, () -> c().maxBreakTicks, v -> c().maxBreakTicks = (int) v);
        toggle("Ramasser les drops", "Va chercher l'objet lache apres chaque minerai.", () -> c().collectDrops, v -> c().collectDrops = v);
        slider("Delai ramassage", "Temps max pour aller chercher un drop.", " ticks", 20, 400, 10, () -> c().collectTimeoutTicks, v -> c().collectTimeoutTicks = (int) v);
        slider("Distance max drop", "Ignore les drops plus loin que ca.", " blocs", 2, 30, 1, () -> c().collectMaxDistance, v -> c().collectMaxDistance = v);
        slider("Rayon de detection", "Distance max des minerais consideres (limite par les chunks charges).", " blocs", 32, 1024, 16, () -> c().scanRadius, v -> { c().scanRadius = (int) v; Miner.I.scanner.requestFullRescan(); });
        slider("Rescan proches (ticks)", "Frequence de re-verification des chunks proches.", "", 20, 600, 20, () -> c().rescanTicks, v -> c().rescanTicks = (int) v);
        slider("Chunks scannes / tick", "Plus haut = detection plus rapide, un peu plus de charge CPU.", "", 1, 32, 1, () -> c().chunksPerTick, v -> c().chunksPerTick = (int) v);
        slider("Penalite verticale", "Plus haut = prefere les minerais a la meme hauteur.", "", 0.5, 4, 0.1, () -> c().verticalWeight, v -> c().verticalWeight = v);
        toggle("Stop si plus de minerai", "Desactive l'auto-mineur quand rien n'est trouve.", () -> c().stopWhenNone, v -> c().stopWhenNone = v);
        text("Motifs de blocs (glob)", "Ex : cobblemon:*dawn_stone_ore (separes par des virgules). Voir aussi l'onglet Blocs.", () -> c().targetPatterns, l -> { c().targetPatterns = l; c().recompile(); });
    }

    private void buildMove() {
        slider("Vitesse de rotation", "Degres max par tick pour tourner la camera.", " deg", 5, 90, 1, () -> c().rotationSpeed, v -> c().rotationSpeed = v);
        slider("Lissage rotation", "Plus bas = mouvement plus doux et naturel.", "", 0.1, 1.0, 0.05, () -> c().rotationEase, v -> c().rotationEase = v);
        slider("Tolerance de visee", "Erreur d'angle acceptee avant de miner.", " deg", 1, 20, 1, () -> c().aimTolerance, v -> c().aimTolerance = v);
        toggle("Sprint", "Court sur les lignes droites (faim > 6).", () -> c().sprint, v -> c().sprint = v);
        slider("Chute max", "Hauteur de chute acceptee par le chemin.", " blocs", 0, 10, 1, () -> c().maxFallDistance, v -> c().maxFallDistance = (int) v);
        toggle("Creuser vers le bas", "Autorise les escaliers et puits creuses vers le bas.", () -> c().allowDigDown, v -> c().allowDigDown = v);
        slider("Cout du creusage", "Plus haut = prefere contourner par des cavernes plutot que creuser.", "x", 0.3, 4, 0.1, () -> c().digCostMultiplier, v -> c().digCostMultiplier = v);
        slider("Noeuds A* max", "Precision/portee de la recherche de chemin.", "", 1000, 30000, 500, () -> c().pathMaxNodes, v -> c().pathMaxNodes = (int) v);
        slider("Budget A* (ms)", "Temps max de calcul d'un chemin par recalcul.", " ms", 5, 60, 1, () -> c().pathBudgetMs, v -> c().pathBudgetMs = (int) v);
        slider("Detection de blocage", "Ticks sans bouger avant de recalculer le chemin.", " ticks", 20, 200, 5, () -> c().maxStuckTicks, v -> c().maxStuckTicks = (int) v);
        slider("Delai max par cible", "Abandonne une cible si ca prend trop longtemps.", " ticks", 200, 6000, 100, () -> c().targetTimeoutTicks, v -> c().targetTimeoutTicks = (int) v);
    }

    private void buildSafety() {
        toggle("Eviter la lave", "Ne casse/traverse rien a cote de lave.", () -> c().avoidLava, v -> c().avoidLava = v);
        toggle("Eviter l'eau", "Ne casse rien a cote d'eau (evite les inondations).", () -> c().avoidWater, v -> c().avoidWater = v);
        slider("Vie minimale", "Arret sous ce nombre de points de vie (2 = 1 coeur).", " pv", 1, 20, 1, () -> c().minHealth, v -> c().minHealth = (int) v);
        slider("Faim minimale", "Arret sous ce niveau de nourriture.", "", 0, 20, 1, () -> c().minFood, v -> c().minFood = (int) v);
        toggle("Stop inventaire plein", "Arret quand plus aucun slot libre.", () -> c().stopWhenInventoryFull, v -> c().stopWhenInventoryFull = v);
        toggle("Stop si degats", "Arret des que tu prends un coup (mob, chute...).", () -> c().stopOnDamage, v -> c().stopOnDamage = v);
        toggle("Pause dans les menus", "Met en pause quand un ecran (inventaire, chat) est ouvert.", () -> c().pauseInScreens, v -> c().pauseInScreens = v);
        text("Blocs proteges (glob)", "Jamais casses en chemin : coffres, lits, spawners...", () -> c().protectedPatterns, l -> { c().protectedPatterns = l; c().recompile(); });
    }

    private void buildVisual() {
        toggle("ESP (contours)", "Dessine les minerais a travers les murs, meme tres loin.", () -> c().esp, v -> c().esp = v);
        slider("Portee ESP", "Distance max des contours.", " blocs", 32, 1024, 16, () -> c().espRange, v -> c().espRange = (int) v);
        slider("Contours max", "Nombre max de contours dessines (les plus proches).", "", 10, 1000, 10, () -> c().espMaxBoxes, v -> c().espMaxBoxes = (int) v);
        toggle("Affichage HUD", "Statut, cible et compteur en haut a gauche.", () -> c().hud, v -> c().hud = v);
        toggle("Desactiver le culling", "Force le rendu de tous les chunks (corrige les trous en xray). Sans effet avec Sodium.", () -> c().noCulling, v -> c().noCulling = v);
        action("Recharger les chunks", "Reconstruit le rendu du monde (corrige les chunks bugges).", "Recharger", CamClient::refreshChunks);
    }

    // ---------------------------------------------------------------- onglet blocs
    private void buildBlocks() {
        TextFieldWidget f = new TextFieldWidget(textRenderer, left + 4, top, panelW - 8, 18, Text.literal("Recherche"));
        f.setPlaceholder(Text.literal("Rechercher un bloc (nom ou id)..."));
        f.setText(search);
        f.setChangedListener(s -> { search = s; listScroll = 0; refilter(); });
        addDrawableChild(f);
        int bw = (panelW - 8) / 3 - 2, y = top + 22;
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(Text.literal(showAll ? "Afficher : tous" : "Afficher : selection"), b -> {
            showAll = !showAll; listScroll = 0; refilter();
            ref[0].setMessage(Text.literal(showAll ? "Afficher : tous" : "Afficher : selection"));
        }).dimensions(left + 4, y, bw, 18).build();
        addDrawableChild(ref[0]);
        addDrawableChild(ButtonWidget.builder(Text.literal("Pierres evolution"), b -> {
            c().targetPatterns = new ArrayList<>(CamConfig.DEFAULT_TARGETS);
            c().includeBlocks.clear(); c().excludeBlocks.clear(); c().recompile(); refilter();
        }).dimensions(left + 6 + bw, y, bw, 18).tooltip(Tooltip.of(Text.literal("Aube, eclat, nuit, glace uniquement (Cobblemon + Megamons)."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Tout decocher"), b -> {
            c().targetPatterns.clear(); c().includeBlocks.clear(); c().excludeBlocks.clear(); c().recompile(); refilter();
        }).dimensions(left + 8 + 2 * bw, y, bw, 18).build());
        refilter();
    }

    private void refilter() {
        listed.clear();
        String q = search.toLowerCase(Locale.ROOT).trim();
        for (Block b : Registries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
            if (!showAll && !c().isTarget(b)) continue;
            if (!q.isEmpty()) {
                String id = Registries.BLOCK.getId(b).toString();
                if (!id.contains(q) && !b.getName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
            }
            listed.add(b);
        }
        listed.sort(Comparator.comparing(b -> Registries.BLOCK.getId(b).toString()));
    }

    // ---------------------------------------------------------------- layout / rendu
    private void layout() {
        if (lastTab == Tab.BLOCKS) return;
        int max = Math.max(0, rows.size() * ROW - (bottom - top));
        scroll = Math.max(0, Math.min(max, scroll));
        for (int i = 0; i < rows.size(); i++) {
            ClickableWidget w = rows.get(i).w();
            int y = top + i * ROW - scroll;
            w.setX(left + panelW - 158);
            w.setY(y + 2);
            w.visible = y >= top - 2 && y + ROW <= bottom + 2;
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101010);
        ctx.fill(left - 4, top - 6, left + panelW + 4, bottom + 4, 0xD0202028);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Cobble Auto Miner 2  -  " + (Miner.I.enabled ? "ACTIF" : "inactif")), width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        if (lastTab == Tab.BLOCKS) { renderBlocks(ctx, mx, my); return; }
        ctx.enableScissor(left, top, left + panelW, bottom);
        for (Row r : rows) if (r.w().visible) ctx.drawText(textRenderer, r.label(), left + 6, r.w().getY() + 6, 0xE0E0E0, false);
        ctx.disableScissor();
        int max = rows.size() * ROW - (bottom - top);
        if (max > 0) {
            int h = bottom - top, bh = Math.max(20, h * h / (rows.size() * ROW));
            int by = top + (int) ((h - bh) * (scroll / (double) max));
            ctx.fill(left + panelW - 2, by, left + panelW, by + bh, 0xFF888888);
        }
    }

    private int listTop() { return top + 58; }

    private void renderBlocks(DrawContext ctx, int mx, int my) {
        int lt = listTop();
        int on = c().targetSet.size();
        ctx.drawText(textRenderer, listed.size() + " blocs affiches  |  " + on + " cibles actives  |  clic = cocher/decocher", left + 6, top + 44, 0xAAAAAA, false);
        ctx.enableScissor(left, lt + 2, left + panelW, bottom);
        int first = listScroll / LROW, y = lt + 2 - listScroll % LROW;
        for (int i = first; i < listed.size() && y < bottom; i++, y += LROW) {
            Block b = listed.get(i);
            boolean sel = c().isTarget(b);
            if (mx >= left && mx < left + panelW && my >= y && my < y + LROW) ctx.fill(left + 2, y, left + panelW - 2, y + LROW - 1, 0x40FFFFFF);
            ctx.drawText(textRenderer, sel ? "[x]" : "[ ]", left + 6, y + 5, sel ? 0x55FF55 : 0x888888, false);
            Item it = b.asItem();
            if (it != Items.AIR) ctx.drawItem(new ItemStack(it), left + 30, y + 1);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(b.getName().getString(), panelW / 2 - 60), left + 50, y + 5, 0xFFFFFF, false);
            String id = textRenderer.trimToWidth(Registries.BLOCK.getId(b).toString(), panelW / 2 - 10);
            ctx.drawText(textRenderer, id, left + panelW - 8 - textRenderer.getWidth(id), y + 5, 0x909090, false);
        }
        ctx.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int lt = listTop();
        if (lastTab == Tab.BLOCKS && button == 0 && my >= lt + 2 && my < bottom && mx >= left && mx < left + panelW) {
            int i = (int) ((my - lt - 2 + listScroll) / LROW);
            if (i >= 0 && i < listed.size()) {
                Block b = listed.get(i);
                c().setTarget(b, !c().isTarget(b));
                Miner.I.scanner.requestFullRescan();
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (lastTab == Tab.BLOCKS) {
            int max = Math.max(0, listed.size() * LROW - (bottom - listTop()));
            listScroll = Math.max(0, Math.min(max, listScroll - (int) (v * LROW * 3)));
        } else {
            scroll -= (int) (v * ROW * 2);
            layout();
        }
        return true;
    }

    @Override
    public void removed() {
        c().recompile();
        CamConfig.save();
        Miner.I.scanner.requestFullRescan();
    }

    @Override
    public boolean shouldPause() { return false; }
}
