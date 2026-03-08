package yelf42.craftword;

import com.google.gson.Gson;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static yelf42.craftword.AmuseLabsDownloader.extractAndDeobfuscateRawc;
import static yelf42.craftword.AmuseLabsDownloader.fetchAmuseLabsToken;

public final class Craftword extends JavaPlugin {

    private final List<CrosswordPlacement> activePlacements = new ArrayList<>();

    public List<CrosswordPlacement> getActivePlacements() {
        return activePlacements;
    }

    public CrosswordPlacement withinPlacement(Entity entity) {
        return activePlacements.stream()
                .filter(placement -> placement.isInsideGrid(entity)).findFirst().orElse(null);
    }
    public CrosswordPlacement withinPlacement(Location location) {
        return activePlacements.stream()
                .filter(placement -> placement.isInsideGrid(location)).findFirst().orElse(null);
    }
    public CrosswordPlacement withinPlacement(Location location, boolean edge) {
        return activePlacements.stream()
                .filter(placement -> placement.isInsideGrid(location, edge)).findFirst().orElse(null);
    }


    public boolean checkPlacementOverlap(CrosswordPlacement cp) {
        return activePlacements.stream().anyMatch((crosswordPlacement -> crosswordPlacement.overlappingBoundingBox(cp)));
    }

    public static CraftwordConfig CONFIG;

    @Override
    public void onEnable() {
        // Resource pack
        File packFile = new File(getDataFolder(), "craftword.zip");
        if (!packFile.exists()) {
            saveResource("craftword.zip", false);
        }

        getServer().getPluginManager().registerEvents(new CraftwordListeners(), this);

        // Crossword folder
        getDataFolder().mkdirs();
        File crosswordsFolder = new File(getDataFolder(), "crosswords");
        crosswordsFolder.mkdirs();

        loadPlacements();
        loadCraftwordConfig();
        registerCommands();
    }

    @Override
    public void onDisable() {
        savePlacements();
        saveCraftwordConfig();
        activePlacements.forEach(CrosswordPlacement::stopTicking);
    }

    private void registerCommands() {
        ///craftword:remove minecraft:overworld 18 91 16
        LiteralCommandNode<CommandSourceStack> remove = Commands.literal("remove").requires(sender -> sender.getSender().isOp())
                .then(Commands.argument("dimension", ArgumentTypes.world())
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .executes(ctx -> {
                                    World world = ctx.getArgument("dimension", World.class);
                                    BlockPositionResolver posResolver = ctx.getArgument("pos", BlockPositionResolver.class);
                                    BlockPosition pos = posResolver.resolve(ctx.getSource());
                                    Location location = new Location(world, pos.blockX(), pos.blockY(), pos.blockZ());

                                    // Emergency entity removal
                                    if (withinPlacement(location) == null) {
                                        Entity entity = world.getNearbyEntities(location, 1, 1, 1, (e -> e.getType() == EntityType.TEXT_DISPLAY)).stream().findFirst().orElse(null);
                                        if (entity != null && entity.getScoreboardTags().contains("craftword")) {
                                            String tag = entity.getScoreboardTags().stream().filter((s) -> !Objects.equals(s, "craftword")).findFirst().orElse("");
                                            if (!tag.isBlank()) {
                                                world.getEntities().forEach(e -> {
                                                    if (e.getScoreboardTags().contains(tag)) {
                                                        e.remove();
                                                    }
                                                });
                                            }
                                        }

                                        return Command.SINGLE_SUCCESS;
                                    }

                                    activePlacements.removeIf(placement -> {
                                        if (placement.isInsideGrid(location)) {
                                            getLogger().info("Removed " + placement.getCrossword().site() + "-" + placement.getCrossword().date() + ", " + placement.getId());
                                            placement.stopTicking();
                                            placement.removeTextDisplays();
                                            return true;
                                        }
                                        return false;
                                    });

                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();

        LiteralCommandNode<CommandSourceStack> clear = Commands.literal("clear_cache").requires(sender -> sender.getSender().isOp())
                .executes(ctx -> {
                    File crosswordsFolder = new File(getDataFolder(), "crosswords");

                    Set<String> safe = activePlacements
                            .stream()
                            .map(crosswordPlacement ->
                                    crosswordPlacement.getCrossword().site().toLowerCase() + "-" + crosswordPlacement.getCrossword().date() + ".json")
                            .collect(Collectors.toSet());

                    File[] files = crosswordsFolder.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (safe.contains(file.getName())) continue;
                            file.delete();
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();

        // Debug
        /*
        LiteralCommandNode<CommandSourceStack> fetch = (Commands.literal("fetch").requires(sender -> sender.getSender().isOp())
        .then(Commands.argument("Site", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    builder.suggest("Universal");
                    builder.suggest("USAToday");
                    builder.suggest("WashingtonPost");
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    String site = ctx.getArgument("Site", String.class);
                    fetchCrossword(source, site, crossword -> {});
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("yyyy-mm-dd", StringArgumentType.word())
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            String site = ctx.getArgument("Site", String.class);
                            String date = ctx.getArgument("yyyy-mm-dd", String.class);

                            try {
                                LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            } catch (DateTimeParseException e) {
                                source.getSender().sendMessage(
                                        Component.text("Invalid date format. Please use yyyy-mm-dd (e.g. 2026-03-05)")
                                );
                                return Command.SINGLE_SUCCESS;
                            }

                            fetchCrossword(source, site, date, crossword -> {});
                            return Command.SINGLE_SUCCESS;
                        }))))
        .build();

        LiteralCommandNode<CommandSourceStack> fetchRaw = (Commands.literal("fetch_raw").requires(sender -> sender.getSender().isOp())
                .then(Commands.argument("Site", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("Universal");
                            builder.suggest("USAToday");
                            builder.suggest("WashingtonPost");
                            builder.suggest("LATimesMini");
                            builder.suggest("LATimes");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            String site = ctx.getArgument("Site", String.class);
                            fetchRawCrossword(source, site);
                            return Command.SINGLE_SUCCESS;
                        })))
                .build();
         */

        LiteralCommandNode<CommandSourceStack> buildNew = (Commands.literal("build_new").requires(sender -> sender.getSender().isOp())
                .then(Commands.argument("dimension", ArgumentTypes.world())
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .then(Commands.argument("Site", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("Universal");
                                            builder.suggest("USAToday");
                                            builder.suggest("WashingtonPost");
                                            builder.suggest("LATimesMini");
                                            builder.suggest("LATimes");
                                            return builder.buildFuture();
                                        })
                            .executes(ctx -> {
                                CommandSourceStack source = ctx.getSource();
                                String site = ctx.getArgument("Site", String.class);
                                World world = ctx.getArgument("dimension", World.class);
                                BlockPositionResolver posResolver = ctx.getArgument("pos", BlockPositionResolver.class);
                                BlockPosition position = posResolver.resolve(ctx.getSource());

                                fetchCrossword(source, site, crossword -> {
                                    CrosswordPlacement placement = new CrosswordPlacement(crossword, position, world);
                                    if (checkPlacementOverlap(placement)) {
                                        source.getSender().sendMessage(Component.text("Overlaps existing placement"));
                                        return;
                                    }
                                    activePlacements.add(placement);
                                    placement.startTicking(this);
                                    placement.generate();
                                });
                                return Command.SINGLE_SUCCESS;
                            })
                                        .then(Commands.argument("yyyy-mm-dd", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String site = ctx.getArgument("Site", String.class);
                                                    String date = ctx.getArgument("yyyy-mm-dd", String.class);
                                                    World world = ctx.getArgument("dimension", World.class);
                                                    BlockPositionResolver posResolver = ctx.getArgument("pos", BlockPositionResolver.class);
                                                    BlockPosition position = posResolver.resolve(ctx.getSource());

                                                    try {
                                                        LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                                                    } catch (DateTimeParseException e) {
                                                        source.getSender().sendMessage(
                                                                Component.text("Invalid date format. Please use yyyy-mm-dd (e.g. 2026-03-05)")
                                                        );
                                                        return Command.SINGLE_SUCCESS;
                                                    }

                                                    fetchCrossword(source, site, date, crossword -> {
                                                        CrosswordPlacement placement = new CrosswordPlacement(crossword, position, world);
                                                        if (checkPlacementOverlap(placement)) {
                                                            source.getSender().sendMessage(Component.text("Overlaps existing placement"));
                                                            return;
                                                        }
                                                        activePlacements.add(placement);
                                                        placement.startTicking(this);
                                                        placement.generate();
                                                    });
                                                    return Command.SINGLE_SUCCESS;
                                        }))))))
                .build();

        LiteralCommandNode<CommandSourceStack> hintLimits = (Commands.literal("hint_limits").requires(sender -> sender.getSender().isOp())
                .then(Commands.argument("min", IntegerArgumentType.integer())
                        .then(Commands.argument("max", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int min = ctx.getArgument("min", Integer.class);
                                    int max = ctx.getArgument("max", Integer.class);
                                    min = Math.max(1, min);
                                    max = Math.max(max, min);
                                    CONFIG = new CraftwordConfig(min, max);
                                    saveCraftwordConfig();
                                    ctx.getSource().getSender().sendMessage(Component.text("Hint limits updated: " + min + " - " + max));
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .build();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            //commands.registrar().register(fetch);
            //commands.registrar().register(fetchRaw);
            commands.registrar().register(clear);
            commands.registrar().register(buildNew);
            commands.registrar().register(remove);
            commands.registrar().register(hintLimits);
        });
    }

    private void fetchCrossword(CommandSourceStack source, String site, Consumer<Crossword> callback) {
        fetchCrossword(source, site, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), callback);
    }

    private void fetchCrossword(CommandSourceStack source, String site, String date, Consumer<Crossword> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File crosswordsFolder = new File(this.getDataFolder(), "crosswords");
            File outputFile = new File(crosswordsFolder, site.toLowerCase() + "-" + date + ".json");

            if (outputFile.exists()) {
                try {
                    String json = new String(java.nio.file.Files.readAllBytes(outputFile.toPath()));
                    Crossword crossword = Crossword.fromJson(json);
                    Bukkit.getScheduler().runTask(this, () -> callback.accept(crossword));
                    source.getSender().sendMessage(
                            Component.text("Fetched existing crossword: " + crossword.site() + " (" + date + ")")
                    );
                } catch (Exception e) {
                    getLogger().severe("Failed to load existing crossword: " + e.getMessage());
                }
            } else {
                try {
                    String urlString = siteURLString(site, date);
                    HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                    conn.setRequestMethod("GET");
                    String userAgent = (site.equals("LATimes") || site.equals("LATimesMini"))
                            ? "Mozilla/5.0" : "Craftword/1.0";
                    conn.setRequestProperty("User-Agent", userAgent);

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream())
                    );
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String rawResponse = response.toString();
                    Crossword crossword;
                    if (site.equals("LATimes") || site.equals("LATimesMini")) {
                        String puzzleJson = extractAndDeobfuscateRawc(rawResponse);
                        crossword = CrosswordParser.parseAmuseLabs(site, date, puzzleJson);
                    } else {
                        crossword = switch (site) {
                            case "Universal"      -> CrosswordParser.parseUniversal(site, date, rawResponse);
                            case "USAToday"       -> CrosswordParser.parseUSAToday(site, date, rawResponse);
                            case "WashingtonPost" -> CrosswordParser.parseWaPo(site, date, rawResponse);
                            default -> throw new IllegalStateException("Unknown site: " + site);
                        };
                    }

                    try (FileWriter writer = new FileWriter(outputFile)) {
                        writer.write(crossword.saveCrossword());
                    }

                    source.getSender().sendMessage(
                            Component.text("Fetched crossword: " + crossword.site() + " (" + date + ")")
                    );

                    // Run callback on main thread since it will likely interact with the world
                    Bukkit.getScheduler().runTask(this, () -> callback.accept(crossword));

                } catch (Exception e) {
                    source.getSender().sendMessage(
                            Component.text("Failed to fetch crossword: " + e.getMessage())
                    );
                }
            }


        });
    }

    private void fetchRawCrossword(CommandSourceStack source, String site) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // URL creation
                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String urlString = siteURLString(site, date);
                HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                conn.setRequestMethod("GET");
                String userAgent = (site.equals("LATimes") || site.equals("LATimesMini"))
                        ? "Mozilla/5.0" : "Craftword/1.0";
                conn.setRequestProperty("User-Agent", userAgent);

                // Read contents
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Save to json in crosswords folder
                File crosswordsFolder = new File(this.getDataFolder(), "crosswords");
                File outputFile = new File(crosswordsFolder, site.toLowerCase() + "-" + date + "-RAW" + siteDataExt(site));
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(response.toString());
                }

                source.getSender().sendMessage(
                        Component.text("Fetched " + site + " crossword for " + date)
                );
            } catch (Exception e) {
                source.getSender().sendMessage(
                        Component.text("Failed to fetch crossword: " + e.getMessage())
                );
            }
        });
    }

    private String siteDataExt(String site) {
        return switch (site) {
            case "Universal", "WashingtonPost" -> ".json";
            case "LATimes", "LATimesMini" -> ".html";
            case "USAToday" -> ".xml";
            default -> throw new IllegalStateException("Unexpected crossword source: " + site);
        };
    }


    private String siteURLString(String site, String date) throws Exception {
        return switch (site) {
            case "Universal" -> "https://gamedata.services.amuniversal.com/c/uucom/l/" +
                    "U2FsdGVkX18YuMv20%2B8cekf85%2Friz1H%2FzlWW4bn0cizt8yclLsp7UYv34S77X0aX%0Axa513fPTc5RoN2wa0h4ED9QWuBURjkqWgHEZey0WFL8%3D" +
                    "/g/fcx/d/" + date + "/data.json";
            case "WashingtonPost" -> {
                LocalDate sundayDate = LocalDate.parse(date);
                LocalDate sunday = sundayDate.minusDays(sundayDate.getDayOfWeek().getValue() % 7);
                yield "https://games-service-prod.site.aws.wapo.pub/crossword/levels/sunday/"
                        + sunday.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            }
            case "USAToday" -> {
                String yymmdd = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("yyMMdd"));
                yield "http://picayune.uclick.com/comics/usaon/data/usaon" + yymmdd + "-data.xml";
            }
            case "LATimesMini" -> {
                String token = fetchAmuseLabsToken("https://lat.amuselabs.com/lat/date-picker?set=latimes-mini");
                String yyyymmdd = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String url = "https://lat.amuselabs.com/lat/crossword?id=latimes-mini-" + yyyymmdd + "&set=latimes-mini";
                yield token != null ? url + "&loadToken=" + token : url;
            }
            case "LATimes" -> {
                String token = fetchAmuseLabsToken("https://lat.amuselabs.com/lat/date-picker?set=latimes");
                String yymmdd = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("yyMMdd"));
                String url = "https://lat.amuselabs.com/lat/crossword?id=tca" + yymmdd + "&set=latimes&token=";
                yield token != null ? url + "&loadToken=" + token : url;
            }
            default -> throw new IllegalStateException("Unexpected crossword source: " + site);
        };
    }

    private record PlacementData(String crossword_date, String crossword_site, String world, int x, int y, int z, String id, String hintId) {}

    private void savePlacements() {
        File file = new File(getDataFolder(), "placements.json");
        List<PlacementData> data = activePlacements.stream()
                .map(p -> new PlacementData(
                        p.getCrossword().date(),
                        p.getCrossword().site(),
                        p.getWorld().getName(),
                        p.getPosition().blockX(),
                        p.getPosition().blockY(),
                        p.getPosition().blockZ(),
                        p.getId().toString(),
                        p.getHintTextDisplay().toString()
                ))
                .toList();
        try (FileWriter writer = new FileWriter(file)) {
            new Gson().toJson(data, writer);
        } catch (IOException e) {
            getLogger().severe("Failed to save placements: " + e.getMessage());
        }
    }

    private void loadPlacements() {
        File file = new File(getDataFolder(), "placements.json");
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            PlacementData[] placements = new Gson().fromJson(reader, PlacementData[].class);
            for (PlacementData data : placements) {
                File crosswordFile = new File(getDataFolder(), "crosswords/" + data.crossword_site().toLowerCase() + "-" + data.crossword_date() + ".json");
                if (!crosswordFile.exists()) {
                    getLogger().warning("Could not find crossword file for placement: " + data.crossword_date() + " " + data.crossword_site());
                    continue;
                }
                String crosswordJson = new String(java.nio.file.Files.readAllBytes(crosswordFile.toPath()));
                Crossword crossword = Crossword.fromJson(crosswordJson);

                World world = Bukkit.getWorld(data.world());
                if (world == null) {
                    getLogger().warning("Could not find world: " + data.world());
                    continue;
                }
                BlockPosition position = Position.block(data.x(), data.y(), data.z());
                CrosswordPlacement placement = new CrosswordPlacement(crossword, position, world, UUID.fromString(data.id()), UUID.fromString(data.hintId()));
                placement.startTicking(this);

                activePlacements.add(placement);
            }
            getLogger().info("Loaded " + activePlacements.size() + " crossword placements.");
        } catch (Exception e) {
            getLogger().severe("Failed to load placements: " + e.getMessage());
        }
    }

    private void saveCraftwordConfig() {
        File file = new File(getDataFolder(), "craftword_config.json");
        try (FileWriter writer = new FileWriter(file)) {
            new Gson().toJson(CONFIG, writer);
        } catch (IOException e) {
            getLogger().severe("Failed to save config: " + e.getMessage());
        }
    }

    private void loadCraftwordConfig() {
        File file = new File(getDataFolder(), "craftword_config.json");
        if (!file.exists()) {
            CONFIG = new CraftwordConfig(1,1);
        } else {
            try (FileReader reader = new FileReader(file)) {
                CONFIG = new Gson().fromJson(reader, CraftwordConfig.class);
                CONFIG = new CraftwordConfig(Math.max(1, CONFIG.minHint()), Math.max(CONFIG.minHint(), CONFIG.maxHint()));
            } catch (IOException e) {
                getLogger().severe("Failed to load config: " + e.getMessage());
            }
        }
    }
}


