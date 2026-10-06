package dev.codex.spark_fix;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The client-side catalogue of vanilla structure names and small fingerprints.
 *
 * <p>Vanilla NBT files remain in the Minecraft jar.  The mod only retains a few
 * distinctive block positions after loading them, so this class is useful to a
 * scanner without redistributing Mojang assets.</p>
 */
public final class StructureFinderCatalog {
    private static final String TEMPLATE_MANIFEST =
            "assets/spark_fix/structure_finder/templates.json";
    private static final String STRUCTURE_PREFIX = "data/minecraft/structure/";
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "spark-fix-structure-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile CompletableFuture<List<Pattern>> patterns;

    /**
     * Blocks that identify a village interior rather than its generic timber
     * shell.  The scanner must never use an ordinary plank/fence fragment as
     * the only village signal: those are common in player-built farms.
     */
    private static final Set<String> VILLAGE_WORKSTATIONS = Set.of(
            "minecraft:smoker",
            "minecraft:blast_furnace", "minecraft:lectern", "minecraft:grindstone",
            "minecraft:stonecutter", "minecraft:fletching_table",
            "minecraft:cartography_table", "minecraft:brewing_stand",
            "minecraft:loom", "minecraft:smithing_table"
    );
    private static final Set<String> NATURAL_TERRACOTTA = Set.of(
            "minecraft:terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
            "minecraft:yellow_terracotta", "minecraft:brown_terracotta", "minecraft:red_terracotta",
            "minecraft:light_gray_terracotta"
    );

    private static final List<Entry> ENTRIES = List.of(
            e("ancient_city", "远古城市", "Ancient City", "overworld"),
            e("bastion_remnant", "堡垒遗迹", "Bastion Remnant", "nether"),
            e("buried_treasure", "埋藏的宝藏", "Buried Treasure", "overworld"),
            e("desert_pyramid", "沙漠神殿", "Desert Pyramid", "overworld"),
            e("end_city", "末地城", "End City", "end"),
            e("fortress", "下界要塞", "Nether Fortress", "nether"),
            e("fossil", "化石", "Fossil", "overworld"),
            e("igloo", "雪屋", "Igloo", "overworld"),
            e("jungle_pyramid", "丛林神庙", "Jungle Pyramid", "overworld"),
            e("mineshaft", "废弃矿井", "Mineshaft", "overworld"),
            e("monument", "海底神殿", "Ocean Monument", "overworld"),
            e("mansion", "林地府邸", "Woodland Mansion", "overworld"),
            e("monster_room", "刷怪房", "Monster Room", "overworld"),
            e("nether_fossil", "下界化石", "Nether Fossil", "nether"),
            e("pillager_outpost", "掠夺者前哨站", "Pillager Outpost", "overworld"),
            e("ruined_portal", "废弃传送门", "Ruined Portal", "any"),
            e("shipwreck", "沉船", "Shipwreck", "overworld"),
            e("stronghold", "要塞", "Stronghold", "overworld"),
            e("sulfur_spring", "硫磺泉", "Sulfur Spring", "overworld"),
            e("swamp_hut", "沼泽小屋", "Swamp Hut", "overworld"),
            e("trail_ruins", "踪迹废墟", "Trail Ruins", "overworld"),
            e("trial_chambers", "试炼密室", "Trial Chambers", "overworld"),
            e("underwater_ruin", "海底废墟", "Ocean Ruins", "overworld"),
            e("village", "村庄", "Village", "overworld")
    );

    private StructureFinderCatalog() {
    }

    private static Entry e(String id, String zh, String en, String dimension) {
        return new Entry(id, zh, en, dimension);
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Loads all selected vanilla pieces once. No Minecraft world is touched. */
    public static CompletableFuture<List<Pattern>> patternsFuture() {
        CompletableFuture<List<Pattern>> result = patterns;
        if (result != null) return result;
        synchronized (StructureFinderCatalog.class) {
            result = patterns;
            if (result == null) {
                result = CompletableFuture.supplyAsync(StructureFinderCatalog::loadPatterns, LOADER)
                        .thenApply(List::copyOf);
                patterns = result;
            }
            return result;
        }
    }

    public record Entry(String id, String zhName, String enName, String dimension) {
        public Component name() {
            return Component.translatableWithFallback("structure_finder.structure." + id, enName);
        }

        public boolean matches(String query) {
            if (query == null || query.isBlank()) return true;
            String needle = normalize(query);
            return normalize(id).contains(needle)
                    || normalize(zhName).contains(needle)
                    || normalize(enName).contains(needle)
                    || id.equals("monster_room") && (normalize("地牢").contains(needle)
                    || normalize("Dungeon").contains(needle));
        }

        public boolean detectable() {
            return true;
        }
    }

    public record Sample(int x, int y, int z, String blockId) {
        public Sample {
            blockId = blockId == null ? "" : blockId;
        }
    }

    public record Pattern(String type, String template, int sizeX, int sizeY, int sizeZ,
                          List<Sample> samples, Sample anchor) {
        public Pattern {
            samples = List.copyOf(samples);
        }
    }

    /**
     * Known vanilla processors replace these template blocks during generation.
     * Only non-air outputs are accepted: removed or unavailable evidence must
     * still count as missing, and replacements never apply across structure types.
     */
    public static List<String> acceptedBlockIds(String type, String expected) {
        if ("buried_treasure".equals(type)) {
            List<String> floor = List.of("minecraft:sandstone", "minecraft:stone", "minecraft:andesite",
                    "minecraft:granite", "minecraft:diorite");
            if ("minecraft:sandstone".equals(expected)) return floor;
            if ("minecraft:sand".equals(expected)) {
                List<String> surrounding = new ArrayList<>(floor);
                surrounding.addAll(List.of("minecraft:sand", "minecraft:gravel", "minecraft:dirt",
                        "minecraft:grass_block", "minecraft:clay", "minecraft:red_sand", "minecraft:red_sandstone"));
                return List.copyOf(surrounding);
            }
        }
        if ("monster_room".equals(type)
                && ("minecraft:cobblestone".equals(expected) || "minecraft:mossy_cobblestone".equals(expected))) {
            return List.of("minecraft:cobblestone", "minecraft:mossy_cobblestone");
        }
        if ("fossil".equals(type) && "minecraft:bone_block".equals(expected)) {
            return List.of(expected, "minecraft:coal_ore", "minecraft:deepslate_diamond_ore");
        }
        if ("ancient_city".equals(type)) {
            return switch (expected) {
                case "minecraft:deepslate_bricks" -> List.of(expected, "minecraft:cracked_deepslate_bricks");
                case "minecraft:deepslate_tiles" -> List.of(expected, "minecraft:cracked_deepslate_tiles");
                default -> List.of(expected);
            };
        }
        if ("trial_chambers".equals(type) && "minecraft:waxed_copper_bulb".equals(expected)) {
            return List.of(expected, "minecraft:waxed_exposed_copper_bulb",
                    "minecraft:waxed_weathered_copper_bulb", "minecraft:waxed_oxidized_copper_bulb");
        }
        if ("trail_ruins".equals(type) && "minecraft:mud_bricks".equals(expected)) {
            return List.of(expected, "minecraft:packed_mud");
        }
        if ("bastion_remnant".equals(type)) {
            return switch (expected) {
                case "minecraft:polished_blackstone_bricks", "minecraft:chiseled_polished_blackstone",
                        "minecraft:gold_block" -> List.of(expected, "minecraft:cracked_polished_blackstone_bricks");
                case "minecraft:cracked_polished_blackstone_bricks" -> List.of(expected, "minecraft:polished_blackstone_bricks");
                case "minecraft:gilded_blackstone" -> List.of(expected, "minecraft:blackstone");
                case "minecraft:blackstone" -> List.of(expected, "minecraft:gilded_blackstone",
                        "minecraft:cracked_polished_blackstone_bricks");
                case "minecraft:magma_block" -> List.of(expected, "minecraft:cracked_polished_blackstone_bricks");
                default -> List.of(expected);
            };
        }
        if (!"ruined_portal".equals(type) || expected == null) return List.of(expected);
        return switch (expected) {
            case "minecraft:obsidian" -> List.of("minecraft:obsidian", "minecraft:crying_obsidian");
            case "minecraft:stone_bricks" -> List.of("minecraft:stone_bricks", "minecraft:cracked_stone_bricks",
                    "minecraft:mossy_stone_bricks", "minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs",
                    "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
                    "minecraft:polished_blackstone_brick_stairs");
            case "minecraft:stone" -> List.of("minecraft:stone", "minecraft:cracked_stone_bricks",
                    "minecraft:mossy_stone_bricks", "minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs",
                    "minecraft:polished_blackstone", "minecraft:polished_blackstone_bricks",
                    "minecraft:cracked_polished_blackstone_bricks", "minecraft:polished_blackstone_brick_stairs");
            case "minecraft:mossy_stone_bricks" -> List.of(expected, "minecraft:polished_blackstone_bricks");
            case "minecraft:cobblestone", "minecraft:mossy_cobblestone" -> List.of(expected,
                    "minecraft:blackstone");
            case "minecraft:cobblestone_stairs", "minecraft:mossy_cobblestone_stairs" -> List.of(expected,
                    "minecraft:blackstone_stairs", "minecraft:stone_slab", "minecraft:stone_brick_slab",
                    "minecraft:mossy_stone_brick_stairs", "minecraft:mossy_stone_brick_slab",
                    "minecraft:polished_blackstone_slab", "minecraft:polished_blackstone_brick_stairs",
                    "minecraft:polished_blackstone_brick_slab");
            case "minecraft:stone_stairs" -> List.of(expected, "minecraft:polished_blackstone_stairs",
                    "minecraft:stone_slab", "minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_stairs",
                    "minecraft:mossy_stone_brick_slab", "minecraft:polished_blackstone_slab",
                    "minecraft:polished_blackstone_brick_stairs", "minecraft:polished_blackstone_brick_slab");
            case "minecraft:cobblestone_slab", "minecraft:mossy_cobblestone_slab" -> List.of(expected,
                    "minecraft:blackstone_slab", "minecraft:mossy_stone_brick_slab",
                    "minecraft:polished_blackstone_brick_slab");
            case "minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall" -> List.of(expected,
                    "minecraft:blackstone_wall", "minecraft:mossy_stone_brick_wall",
                    "minecraft:polished_blackstone_brick_wall");
            case "minecraft:chiseled_stone_bricks" -> List.of("minecraft:chiseled_stone_bricks",
                    "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks",
                    "minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs",
                    "minecraft:chiseled_polished_blackstone", "minecraft:polished_blackstone_bricks",
                    "minecraft:cracked_polished_blackstone_bricks", "minecraft:polished_blackstone_brick_stairs");
            case "minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs" -> List.of(expected,
                    "minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_stairs",
                    "minecraft:mossy_stone_brick_slab", "minecraft:stone_slab",
                    "minecraft:polished_blackstone_brick_stairs", "minecraft:polished_blackstone_brick_slab");
            case "minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_slab" -> List.of(expected,
                    "minecraft:mossy_stone_brick_slab", "minecraft:polished_blackstone_brick_slab");
            case "minecraft:smooth_stone_slab" -> List.of("minecraft:smooth_stone_slab",
                    "minecraft:mossy_stone_brick_slab", "minecraft:polished_blackstone_slab",
                    "minecraft:polished_blackstone_brick_slab");
            case "minecraft:stone_slab" -> List.of("minecraft:stone_slab", "minecraft:mossy_stone_brick_slab",
                    "minecraft:polished_blackstone_slab", "minecraft:polished_blackstone_brick_slab");
            case "minecraft:stone_brick_wall", "minecraft:mossy_stone_brick_wall" -> List.of(expected,
                    "minecraft:mossy_stone_brick_wall", "minecraft:polished_blackstone_brick_wall");
            case "minecraft:iron_bars" -> List.of("minecraft:iron_bars", "minecraft:iron_chain");
            case "minecraft:cracked_stone_bricks" -> List.of("minecraft:cracked_stone_bricks",
                    "minecraft:cracked_polished_blackstone_bricks");
            case "minecraft:netherrack" -> List.of("minecraft:netherrack", "minecraft:magma_block");
            default -> List.of(expected);
        };
    }

    /** A stable marker used to reject generic village-looking player builds. */
    public static boolean isVillageAnchor(String id) {
        if (id == null) return false;
        String value = id.toLowerCase(Locale.ROOT);
        return value.equals("minecraft:bell")
                || value.endsWith("_bed")
                || VILLAGE_WORKSTATIONS.contains(value);
    }

    /** A badlands terracotta layer alone cannot establish an archaeological ruin. */
    public static boolean isTrailRuinsAnchor(String id) {
        return isStrong(id) && !NATURAL_TERRACOTTA.contains(id.toLowerCase(Locale.ROOT));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    }

    private static List<Pattern> loadPatterns() {
        Map<String, List<String>> manifest = readManifest();
        List<Pattern> result = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.detectable()) {
                Pattern process = processPattern(entry.id());
                if (process != null) result.add(process);
                if (entry.id().equals("monster_room")) result.addAll(monsterRoomPatterns());
                if (entry.id().equals("fortress")) result.add(fortressCrossingPattern());
                if (entry.id().equals("mineshaft")) {
                    Pattern normal = process;
                    List<Sample> mesa = normal.samples().stream()
                            .map(s -> new Sample(s.x(), s.y(), s.z(),
                                    s.blockId().replace("minecraft:oak_", "minecraft:dark_oak_")))
                            .toList();
                    result.add(new Pattern("mineshaft", "vanilla:mineshaft_mesa",
                            normal.sizeX(), normal.sizeY(), normal.sizeZ(), mesa, mesa.get(0)));
                }
            }
            String family = familyFor(entry.id());
            List<String> paths = manifest.getOrDefault(family, List.of());
            // Keep every qualifying piece and every palette. A single "best"
            // village/shipwreck piece cannot cover other biomes or damaged variants.
            Set<List<Sample>> seen = new HashSet<>();
            for (String path : paths) {
                if (entry.id().equals("village") && !isVillageTemplateAllowed(path)) continue;
                for (Pattern candidate : readPieces(entry.id(), path)) {
                    if (seen.add(candidate.samples())) result.add(candidate);
                }
            }
        }
        return result;
    }

    private static String familyFor(String id) {
        return switch (id) {
            case "bastion_remnant" -> "bastion";
            case "end_city" -> "end_city";
            case "fortress" -> "fortress";
            case "fossil" -> "fossil";
            case "igloo" -> "igloo";
            case "jungle_pyramid", "desert_pyramid", "swamp_hut", "stronghold",
                    "mineshaft", "monument", "buried_treasure", "monster_room" -> "";
            case "nether_fossil" -> "nether_fossils";
            case "pillager_outpost" -> "pillager_outpost";
            case "ruined_portal" -> "ruined_portal";
            case "shipwreck" -> "shipwreck";
            case "sulfur_spring" -> "spring";
            case "underwater_ruin" -> "underwater_ruin";
            case "trial_chambers" -> "trial_chambers";
            case "village" -> "village";
            case "trail_ruins" -> "trail_ruins";
            case "ancient_city" -> "ancient_city";
            case "mansion" -> "woodland_mansion";
            default -> id;
        };
    }

    private static boolean isVillageTemplateAllowed(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        // These pieces are stand-alone scenery or terrain and are especially
        // prone to matching player-built crop fields, roads, lamps and pens.
        if (value.contains("/streets/") || value.contains("/terminators/")
                || value.contains("/villagers/") || value.contains("/animals/")
                || value.contains("/decays/") || value.contains("/common/")
                || value.contains("farm") || value.contains("animal_pen")
                || value.contains("grass_") || value.contains("lamp")
                || value.contains("decoration") || value.endsWith("well_bottom")) {
            return false;
        }
        return value.contains("/houses/") || value.contains("/town_centers/");
    }

    private static Map<String, List<String>> readManifest() {
        InputStream stream = StructureFinderCatalog.class.getClassLoader()
                .getResourceAsStream(TEMPLATE_MANIFEST);
        if (stream == null) return Map.of();
        try (stream; Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject groups = root.getAsJsonObject("templates");
            Map<String, List<String>> result = new LinkedHashMap<>();
            if (groups != null) {
                for (Map.Entry<String, JsonElement> group : groups.entrySet()) {
                    List<String> paths = new ArrayList<>();
                    if (group.getValue().isJsonArray()) {
                        group.getValue().getAsJsonArray().forEach(value -> {
                            if (value.isJsonPrimitive()) paths.add(value.getAsString());
                        });
                    }
                    result.put(group.getKey(), List.copyOf(paths));
                }
            }
            return result;
        } catch (RuntimeException | IOException ignored) {
            return Map.of();
        }
    }

    private static List<Pattern> readPieces(String type, String relativePath) {
        String resource = STRUCTURE_PREFIX + relativePath + ".nbt";
        InputStream stream = StructureFinderCatalog.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) return List.of();
        try (stream) {
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.create(16L * 1024L * 1024L));
            int[] size = vector(root, "size");
            ListTag blocks = root.getListOrEmpty("blocks");
            List<ListTag> palettes = new ArrayList<>();
            ListTag palette = root.getListOrEmpty("palette");
            if (!palette.isEmpty()) palettes.add(palette);
            for (Tag tag : root.getListOrEmpty("palettes")) {
                tag.asList().filter(value -> !value.isEmpty()).ifPresent(palettes::add);
            }
            List<Pattern> result = new ArrayList<>();
            for (int paletteIndex = 0; paletteIndex < palettes.size(); paletteIndex++) {
                ListTag current = palettes.get(paletteIndex);
                List<RawSample> raw = new ArrayList<>();
                for (Tag tag : blocks) {
                    CompoundTag block = tag.asCompound().orElse(null);
                    if (block == null) continue;
                    int state = block.getIntOr("state", -1);
                    if (state < 0 || state >= current.size()) continue;
                    String id = current.getCompoundOrEmpty(state).getStringOr("Name", "");
                    int[] pos = vector(block, "pos");
                    if (pos.length == 3 && isStrong(id)
                            && (!type.equals("ruined_portal") || isStablePortalSample(id))) {
                        raw.add(new RawSample(pos[0], pos[1], pos[2], id));
                    }
                }
                List<Sample> samples = selectSamples(raw);
                if (samples.size() < 8) continue;
                // A village fingerprint without a bed, bell or workstation is
                // only a generic wooden fragment and causes farm false positives.
                if (type.equals("village")
                        && samples.stream().noneMatch(sample -> isVillageAnchor(sample.blockId()))) continue;
                if (type.equals("trail_ruins")
                        && samples.stream().noneMatch(sample -> isTrailRuinsAnchor(sample.blockId()))) continue;
                // A decorative fence/roof/lamp stub is not a structure. Bone
                // fossils legitimately contain one block type, but retain their
                // complete dispersed shape rather than one arbitrary small patch.
                if (!type.equals("fossil") && !type.equals("nether_fossil")
                        && samples.stream().map(Sample::blockId).distinct().count() < 2) continue;
                if (span(samples) < 5 && !isCompactTrailTop(relativePath)) continue;
                Sample anchor = type.equals("ruined_portal")
                        ? samples.stream().filter(s -> s.blockId().equals("minecraft:obsidian"))
                        .findFirst().orElse(samples.get(0))
                        : samples.stream().max(Comparator.comparingInt(s -> rarity(s.blockId())))
                        .orElse(samples.get(0));
                String name = relativePath + (palettes.size() > 1 ? "#" + paletteIndex : "");
                result.add(new Pattern(type, name, atLeast(size, 0), atLeast(size, 1),
                        atLeast(size, 2), samples, anchor));
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    // Structure NBT normally uses TAG_List<Int>, not TAG_Int_Array.
    private static int[] vector(CompoundTag compound, String key) {
        int[] array = compound.getIntArray(key).orElse(null);
        if (array != null) return array;
        ListTag list = compound.getListOrEmpty(key);
        if (list.size() != 3) return new int[0];
        return new int[]{list.getIntOr(0, 0), list.getIntOr(1, 0), list.getIntOr(2, 0)};
    }

    private record RawSample(int x, int y, int z, String id) { }

    private static List<Sample> selectSamples(List<RawSample> raw) {
        Map<String, List<RawSample>> byBlock = new LinkedHashMap<>();
        for (RawSample sample : raw) byBlock.computeIfAbsent(sample.id, ignored -> new ArrayList<>()).add(sample);
        List<String> blocks = new ArrayList<>(byBlock.keySet());
        blocks.sort(Comparator.comparingInt((String id) -> rarity(id) * 1000 - byBlock.get(id).size()).reversed());
        List<Sample> selected = new ArrayList<>();
        Set<String> positions = new HashSet<>();
        // Round-robin between block types preserves a composition, then spread
        // positions out. Taking the first 24 bricks creates a weak compact patch.
        boolean added = true;
        while (added && selected.size() < 24) {
            added = false;
            for (String id : blocks) {
                RawSample farthest = null;
                long farthestDistance = -1;
                for (RawSample candidate : byBlock.get(id)) {
                    if (positions.contains(position(candidate))) continue;
                    long distance = selected.isEmpty() ? 0 : Long.MAX_VALUE;
                    for (Sample existing : selected) {
                        long dx = (long) candidate.x - existing.x();
                        long dy = (long) candidate.y - existing.y();
                        long dz = (long) candidate.z - existing.z();
                        distance = Math.min(distance, dx * dx + dy * dy + dz * dz);
                    }
                    if (distance > farthestDistance) {
                        farthestDistance = distance;
                        farthest = candidate;
                    }
                }
                if (farthest == null) continue;
                positions.add(position(farthest));
                selected.add(new Sample(farthest.x, farthest.y, farthest.z, farthest.id));
                added = true;
                if (selected.size() == 24) break;
            }
        }
        return selected;
    }

    private static String position(RawSample sample) {
        return sample.x + ":" + sample.y + ":" + sample.z;
    }

    private static int atLeast(int[] size, int index) {
        return size.length > index ? Math.max(1, size[index]) : 1;
    }

    private static int span(List<Sample> samples) {
        int minX = samples.stream().mapToInt(Sample::x).min().orElse(0);
        int minY = samples.stream().mapToInt(Sample::y).min().orElse(0);
        int minZ = samples.stream().mapToInt(Sample::z).min().orElse(0);
        int maxX = samples.stream().mapToInt(Sample::x).max().orElse(0);
        int maxY = samples.stream().mapToInt(Sample::y).max().orElse(0);
        int maxZ = samples.stream().mapToInt(Sample::z).max().orElse(0);
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    private static boolean isCompactTrailTop(String path) {
        return path.startsWith("trail_ruins/tower/tower_top_")
                && !path.endsWith("tower_top_5");
    }

    private static boolean isStablePortalSample(String id) {
        // Gold can disappear (30%) and chests are loot-bearing entities rather
        // than an architectural fingerprint. The frame and its processed stone
        // parts are retained and checked through acceptedBlockIds().
        return !id.equals("minecraft:gold_block") && !id.equals("minecraft:chest")
                && !id.equals("minecraft:netherrack") && !id.equals("minecraft:magma_block");
    }

    private static boolean isStrong(String id) {
        if (id == null || id.isBlank()) return false;
        String value = id.toLowerCase(Locale.ROOT);
        if (value.equals("minecraft:air") || value.endsWith(":cave_air") || value.endsWith(":void_air")
                || value.endsWith(":structure_void") || value.endsWith(":structure_block")
                || value.endsWith(":jigsaw")) return false;
        if (value.contains("_ore") || value.endsWith(":stone") || value.endsWith(":deepslate")
                || value.endsWith(":netherrack") || value.endsWith(":end_stone")
                || value.endsWith(":dirt") || value.endsWith(":sand") || value.endsWith(":gravel")
                || value.endsWith(":granite") || value.endsWith(":diorite") || value.endsWith(":andesite")
                || value.endsWith(":grass_block") || value.endsWith(":snow") || value.endsWith(":snow_block")
                || value.endsWith(":ice") || value.endsWith(":packed_ice") || value.endsWith(":blue_ice")
                || value.endsWith(":short_grass") || value.endsWith(":tall_grass")
                || value.endsWith(":fern") || value.endsWith(":large_fern")
                || value.endsWith("_leaves") || value.endsWith(":vine") || value.endsWith(":dead_bush")
                || value.endsWith(":coal_block")
                || value.endsWith(":water") || value.endsWith(":lava")) return false;
        return true;
    }

    private static int rarity(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if (value.contains("spawner") || value.contains("end_portal") || value.contains("chest")) return 100;
        if (isVillageAnchor(value)) return 92;
        if (value.contains("dispenser") || value.contains("dropper") || value.contains("tnt")
                || value.contains("gold_block") || value.contains("prismarine")
                || value.contains("terracotta") || value.contains("shroomlight")
                || value.contains("sulfur") || value.contains("cinnabar")) return 80;
        if (value.contains("lantern") || value.contains("sea_lantern") || value.contains("magma")) return 65;
        if (value.contains("fence") || value.contains("trapdoor") || value.contains("sculk")) return 55;
        if (value.contains("brick") || value.contains("purpur") || value.contains("cobweb")) return 45;
        return 20;
    }

    private static Pattern processPattern(String type) {
        List<Sample> samples = new ArrayList<>();
        switch (type) {
            case "buried_treasure" -> {
                samples.add(new Sample(2, 2, 2, "minecraft:chest"));
                samples.add(new Sample(2, 1, 2, "minecraft:sandstone"));
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        for (int z = 0; z < 5; z++) {
                            if (x == 2 && z == 2 && (y == 1 || y == 2)) continue;
                            samples.add(new Sample(x, y, z, "minecraft:sand"));
                        }
                    }
                }
            }
            case "desert_pyramid" -> {
                return desertPyramidPattern();
            }
            case "jungle_pyramid" -> {
                samples.add(new Sample(3, -2, 1, "minecraft:dispenser"));
                samples.add(new Sample(9, -2, 3, "minecraft:dispenser"));
                samples.add(new Sample(8, -3, 3, "minecraft:chest"));
                samples.add(new Sample(9, -3, 10, "minecraft:chest"));
                samples.add(new Sample(8, -2, 11, "minecraft:chiseled_stone_bricks"));
                samples.add(new Sample(9, -2, 11, "minecraft:chiseled_stone_bricks"));
                samples.add(new Sample(10, -2, 11, "minecraft:chiseled_stone_bricks"));
                samples.add(new Sample(9, -2, 8, "minecraft:sticky_piston"));
                samples.add(new Sample(10, -2, 8, "minecraft:sticky_piston"));
                samples.add(new Sample(10, -1, 8, "minecraft:sticky_piston"));
                samples.add(new Sample(10, -2, 10, "minecraft:repeater"));
                samples.add(new Sample(1, -3, 8, "minecraft:tripwire_hook"));
                samples.add(new Sample(4, -3, 8, "minecraft:tripwire_hook"));
                samples.add(new Sample(7, -3, 1, "minecraft:tripwire_hook"));
                samples.add(new Sample(7, -3, 5, "minecraft:tripwire_hook"));
            }
            case "swamp_hut" -> {
                samples.add(new Sample(1, 0, 2, "minecraft:oak_log"));
                samples.add(new Sample(5, 0, 2, "minecraft:oak_log"));
                samples.add(new Sample(1, 0, 7, "minecraft:oak_log"));
                samples.add(new Sample(5, 0, 7, "minecraft:oak_log"));
                samples.add(new Sample(3, 2, 6, "minecraft:crafting_table"));
                samples.add(new Sample(4, 2, 6, "minecraft:cauldron"));
                samples.add(new Sample(2, 3, 2, "minecraft:oak_fence"));
                samples.add(new Sample(3, 3, 7, "minecraft:oak_fence"));
                samples.add(new Sample(1, 3, 5, "minecraft:potted_red_mushroom"));
                samples.add(new Sample(1, 2, 1, "minecraft:oak_fence"));
                samples.add(new Sample(5, 2, 1, "minecraft:oak_fence"));
                for (int x : new int[]{0, 6}) {
                    for (int z : new int[]{1, 8}) {
                        samples.add(new Sample(x, 4, z, "minecraft:spruce_stairs"));
                    }
                }
            }
            case "stronghold" -> {
                samples.add(new Sample(4, 3, 8, "minecraft:end_portal_frame"));
                samples.add(new Sample(5, 3, 8, "minecraft:end_portal_frame"));
                samples.add(new Sample(6, 3, 8, "minecraft:end_portal_frame"));
                samples.add(new Sample(4, 3, 12, "minecraft:end_portal_frame"));
                samples.add(new Sample(5, 3, 12, "minecraft:end_portal_frame"));
                samples.add(new Sample(6, 3, 12, "minecraft:end_portal_frame"));
                for (int x : new int[]{3, 7}) {
                    for (int z = 9; z <= 11; z++) {
                        samples.add(new Sample(x, 3, z, "minecraft:end_portal_frame"));
                    }
                }
                samples.add(new Sample(5, 3, 6, "minecraft:spawner"));
                for (int x = 4; x <= 6; x++) {
                    samples.add(new Sample(x, 1, 4, "minecraft:stone_brick_stairs"));
                    samples.add(new Sample(x, 2, 5, "minecraft:stone_brick_stairs"));
                    // The silverfish spawner replaces the center upper stair.
                    if (x != 5) samples.add(new Sample(x, 3, 6, "minecraft:stone_brick_stairs"));
                }
            }
            case "fortress" -> {
                samples.add(new Sample(3, 5, 5, "minecraft:spawner"));
                samples.add(new Sample(1, 6, 3, "minecraft:nether_brick_fence"));
                samples.add(new Sample(5, 6, 3, "minecraft:nether_brick_fence"));
                samples.add(new Sample(0, 6, 4, "minecraft:nether_brick_fence"));
                samples.add(new Sample(6, 6, 4, "minecraft:nether_brick_fence"));
                samples.add(new Sample(1, 5, 2, "minecraft:nether_bricks"));
                samples.add(new Sample(5, 5, 2, "minecraft:nether_bricks"));
                samples.add(new Sample(3, 5, 8, "minecraft:nether_bricks"));
                for (int x : new int[]{0, 6}) {
                    for (int z : new int[]{6, 8}) {
                        samples.add(new Sample(x, 6, z, "minecraft:nether_brick_fence"));
                    }
                }
                samples.add(new Sample(3, 8, 8, "minecraft:nether_brick_fence"));
            }
            case "monument" -> {
                for (int y : new int[]{3, 6}) {
                    for (int x : new int[]{6, 9}) {
                        for (int z : new int[]{6, 9}) {
                            samples.add(new Sample(x, y, z, "minecraft:sea_lantern"));
                        }
                    }
                }
                for (int y : new int[]{4, 5}) {
                    for (int x : new int[]{7, 8}) {
                        for (int z : new int[]{7, 8}) {
                            samples.add(new Sample(x, y, z, "minecraft:gold_block"));
                        }
                    }
                }
                for (int x : new int[]{6, 9}) {
                    for (int z : new int[]{7, 8}) {
                        samples.add(new Sample(x, 4, z, "minecraft:dark_prismarine"));
                    }
                }
            }
            case "mineshaft" -> {
                // Two corridor supports spaced five blocks apart. Cobwebs,
                // rails and center beams are random and must not be required.
                for (int z : new int[]{2, 7}) {
                    for (int x : new int[]{0, 2}) {
                        samples.add(new Sample(x, 0, z, "minecraft:oak_fence"));
                        samples.add(new Sample(x, 1, z, "minecraft:oak_fence"));
                        samples.add(new Sample(x, 2, z, "minecraft:oak_planks"));
                    }
                }
            }
            default -> {
                return null;
            }
        }
        // Coordinate origin is the smallest sampled corner. This includes
        // basement pieces with negative vanilla local Y without drawing their
        // inferred bounds several blocks above the fingerprint.
        int minX = samples.stream().mapToInt(Sample::x).min().orElse(0);
        int minY = samples.stream().mapToInt(Sample::y).min().orElse(0);
        int minZ = samples.stream().mapToInt(Sample::z).min().orElse(0);
        List<Sample> normalized = samples.stream().map(s -> new Sample(s.x() - minX,
                s.y() - minY, s.z() - minZ, s.blockId())).toList();
        Sample anchor = normalized.stream().max(Comparator.comparingInt(s -> rarity(s.blockId())))
                .orElse(null);
        return new Pattern(type, "vanilla:" + type,
                normalized.stream().mapToInt(Sample::x).max().orElse(0) + 1,
                normalized.stream().mapToInt(Sample::y).max().orElse(0) + 1,
                normalized.stream().mapToInt(Sample::z).max().orElse(0) + 1, normalized, anchor);
    }

    /** Minecraft 26.2 DesertPyramidPiece coordinates, with the Y=-14 base as origin. */
    private static Pattern desertPyramidPattern() {
        List<Sample> samples = new ArrayList<>();
        Sample anchor = new Sample(10, 1, 10, "minecraft:tnt");
        samples.add(anchor);
        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                if (x != 10 || z != 10) samples.add(new Sample(x, 1, z, "minecraft:tnt"));
            }
        }
        samples.add(new Sample(10, 2, 10, "minecraft:cut_sandstone"));
        samples.add(new Sample(10, 3, 10, "minecraft:stone_pressure_plate"));
        for (int offset : new int[]{-2, 2}) {
            samples.add(new Sample(10 + offset, 3, 10, "minecraft:chest"));
            samples.add(new Sample(10, 3, 10 + offset, "minecraft:chest"));
        }
        for (int x : new int[]{8, 12}) {
            for (int z : new int[]{8, 12}) {
                samples.add(new Sample(x, 0, z, "minecraft:cut_sandstone"));
                samples.add(new Sample(x, 4, z, "minecraft:chiseled_sandstone"));
                samples.add(new Sample(x, 5, z, "minecraft:cut_sandstone"));
                samples.add(new Sample(x, 6, z, "minecraft:sandstone"));
            }
        }
        samples.add(new Sample(10, 14, 10, "minecraft:blue_terracotta"));
        int[][] orange = {{10, 7}, {10, 8}, {9, 9}, {11, 9}, {8, 10}, {12, 10},
                {7, 10}, {13, 10}, {9, 11}, {11, 11}, {10, 12}, {10, 13}};
        for (int[] point : orange) {
            samples.add(new Sample(point[0], 14, point[1], "minecraft:orange_terracotta"));
        }
        for (int x : new int[]{0, 20}) {
            for (int y : new int[]{2, 3, 5, 7}) {
                samples.add(new Sample(x, y + 14, 2, "minecraft:orange_terracotta"));
            }
        }
        // The trap and full structure bounds stay in the same vanilla frame.
        // Do not infer this anchor from sample rarity: the center TNT is unique.
        return new Pattern("desert_pyramid", "vanilla:desert_pyramid", 21, 29, 21, samples, anchor);
    }

    private static List<Pattern> monsterRoomPatterns() {
        List<Pattern> result = new ArrayList<>();
        for (int width : new int[]{7, 9}) {
            for (int depth : new int[]{7, 9}) {
                List<Sample> samples = new ArrayList<>();
                Sample spawner = new Sample(width / 2, 1, depth / 2, "minecraft:spawner");
                samples.add(spawner);
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) samples.add(new Sample(x, 0, z, "minecraft:cobblestone"));
                }
                // Floors have random cobblestone/mossy variants. Open walls,
                // natural ceilings and optional chests are not reliable samples.
                result.add(new Pattern("monster_room", "vanilla:monster_room_" + width + "x" + depth,
                        width, 6, depth, samples, spawner));
            }
        }
        return List.copyOf(result);
    }

    /** The 26.2 BridgeCrossing has four identical arms, with no random blocks. */
    private static Pattern fortressCrossingPattern() {
        List<Sample> samples = new ArrayList<>();
        // The two side rails form an eight-point symmetry orbit. Interleave
        // arms so an ordinary straight bridge fails before checking its length.
        for (int[] point : new int[][]{{7, 2}, {2, 7}, {11, 16}, {16, 11},
                {11, 2}, {2, 11}, {7, 16}, {16, 7}}) {
            samples.add(new Sample(point[0], 5, point[1], "minecraft:nether_bricks"));
        }
        // Bridge surfaces, lower supports and the four end foundations.
        // Every orbit is invariant under all X/Z rotations and mirrors.
        for (int[] layer : new int[][]{{4, 0}, {3, 1}, {2, 2}, {0, 1}}) {
            int y = layer[0], edge = layer[1];
            for (int[] point : new int[][]{{9, edge}, {edge, 9}, {9, 18 - edge}, {18 - edge, 9}}) {
                samples.add(new Sample(point[0], y, point[1], "minecraft:nether_bricks"));
            }
        }
        return new Pattern("fortress", "vanilla:fortress_bridge_crossing", 19, 10, 19,
                samples, samples.getFirst());
    }
}
