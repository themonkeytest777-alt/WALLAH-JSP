package com.cobbleautominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class CamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final List<String> DEFAULT_TARGETS = List.of(
            "cobblemon:*dawn_stone_ore", "cobblemon:*dusk_stone_ore",
            "cobblemon:*shiny_stone_ore", "cobblemon:*ice_stone_ore",
            "megamons:*dawn_stone_ore", "megamons:*dusk_stone_ore",
            "megamons:*shiny_stone_ore", "megamons:*ice_stone_ore");
    public static final List<String> DEFAULT_PROTECTED = List.of(
            "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault",
            "minecraft:*chest", "minecraft:barrel", "minecraft:*shulker_box", "minecraft:*_bed",
            "minecraft:end_portal*", "minecraft:reinforced_deepslate", "minecraft:*command_block",
            "minecraft:beacon", "minecraft:ender_chest", "minecraft:*furnace", "minecraft:crafting_table",
            "minecraft:*sign", "minecraft:*door", "minecraft:*rail");

    // IMPORTANT : doit etre declare APRES DEFAULT_TARGETS et DEFAULT_PROTECTED (ordre d'initialisation statique)
    public static CamConfig I = new CamConfig();

    // ---- Blocs ----
    public List<String> targetPatterns = new ArrayList<>(DEFAULT_TARGETS);
    public List<String> includeBlocks = new ArrayList<>();
    public List<String> excludeBlocks = new ArrayList<>();
    public List<String> protectedPatterns = new ArrayList<>(DEFAULT_PROTECTED);

    // ---- Minage ----
    public boolean removeBreakCooldown = true;
    public boolean autoTool = true;
    public int minToolDurability = 8;
    public double reach = 4.3;
    public int tunnelLookahead = 3;
    public int maxBreakTicks = 400;
    public boolean collectDrops = true;
    public int collectTimeoutTicks = 100;
    public double collectMaxDistance = 12;
    public int scanRadius = 320;
    public int rescanTicks = 100;
    public int chunksPerTick = 8;
    public double verticalWeight = 1.5;
    public boolean stopWhenNone = false;

    // ---- Mouvement ----
    public boolean instantAim = true;
    public int cfgVersion = 0;
    public double rotationSpeed = 40;
    public double rotationEase = 0.5;
    public double aimTolerance = 6;
    public boolean sprint = true;
    public int maxFallDistance = 3;
    public boolean allowDigDown = true;
    public double digCostMultiplier = 1.0;
    public int pathMaxNodes = 9000;
    public int pathBudgetMs = 20;
    public int maxStuckTicks = 50;
    public int targetTimeoutTicks = 2400;

    // ---- Securite ----
    public boolean avoidLava = true;
    public boolean avoidWater = true;
    public int minHealth = 8;
    public int minFood = 6;
    public boolean stopWhenInventoryFull = true;
    public boolean stopOnDamage = true;
    public boolean pauseInScreens = true;

    // ---- Visuel ----
    public boolean esp = true;
    public int espRange = 256;
    public int espMaxBoxes = 200;
    public boolean hud = true;
    public boolean noCulling = true;

    // ---- runtime ----
    public transient Set<Block> targetSet = new HashSet<>();
    public transient int targetVersion = 0;
    private transient Map<Block, Boolean> protCache = new HashMap<>();
    private transient List<Pattern> protPats = new ArrayList<>();

    public static Pattern glob(String g) {
        g = g.trim().toLowerCase(Locale.ROOT);
        if (!g.contains(":")) g = "*:" + g;
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString());
    }

    private static List<Pattern> compile(List<String> l) {
        List<Pattern> out = new ArrayList<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(glob(s));
        return out;
    }

    private static boolean any(List<Pattern> pats, String id) {
        for (Pattern p : pats) if (p.matcher(id).matches()) return true;
        return false;
    }

    public void recompile() {
        if (targetPatterns == null) targetPatterns = new ArrayList<>();
        if (includeBlocks == null) includeBlocks = new ArrayList<>();
        if (excludeBlocks == null) excludeBlocks = new ArrayList<>();
        if (protectedPatterns == null) protectedPatterns = new ArrayList<>();
        Set<Block> set = new HashSet<>();
        List<Pattern> pats = compile(targetPatterns);
        for (Block b : Registries.BLOCK) {
            if (b == Blocks.AIR) continue;
            String id = Registries.BLOCK.getId(b).toString();
            boolean on = any(pats, id);
            if (includeBlocks.contains(id)) on = true;
            if (excludeBlocks.contains(id)) on = false;
            if (on) set.add(b);
        }
        targetSet = set;
        protPats = compile(protectedPatterns);
        protCache = new HashMap<>();
        targetVersion++;
    }

    public boolean isTarget(Block b) { return targetSet.contains(b); }

    public boolean isProtected(Block b) {
        return protCache.computeIfAbsent(b, k -> any(protPats, Registries.BLOCK.getId(k).toString()));
    }

    public void setTarget(Block b, boolean on) {
        String id = Registries.BLOCK.getId(b).toString();
        if (on) {
            excludeBlocks.remove(id);
            if (!matchesPatterns(id) && !includeBlocks.contains(id)) includeBlocks.add(id);
        } else {
            includeBlocks.remove(id);
            if (matchesPatterns(id) && !excludeBlocks.contains(id)) excludeBlocks.add(id);
        }
        recompile();
    }

    private boolean matchesPatterns(String id) { return any(compile(targetPatterns), id); }

    // ---- fichier ----
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("cobbleautominer-v2.json"); }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    CamConfig c = GSON.fromJson(r, CamConfig.class);
                    if (c != null) {
                        if (c.cfgVersion < 3) {
                            c.cfgVersion = 3;
                            c.instantAim = true;
                            c.maxStuckTicks = 30;
                            c.collectTimeoutTicks = 60;
                        }
                        I = c;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[CobbleAutoMiner] Config illisible, valeurs par defaut : " + e);
            I = new CamConfig();
        }
        save();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(I, w);
        } catch (Exception e) {
            System.err.println("[CobbleAutoMiner] Impossible d'ecrire la config : " + e);
        }
    }

    public static void reset() {
        I = new CamConfig();
        I.recompile();
        save();
    }
}
