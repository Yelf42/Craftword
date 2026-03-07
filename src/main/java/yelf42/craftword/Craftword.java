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
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;

public final class Craftword extends JavaPlugin implements Listener {

    private final List<CrosswordPlacement> activePlacements = new ArrayList<>();

    public List<CrosswordPlacement> getActivePlacements() {
        return activePlacements;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info(" $$$ Craftworld Start");

        // Resource pack
        File packFile = new File(getDataFolder(), "craftword.zip");
        if (!packFile.exists()) {
            saveResource("craftword.zip", false);
        }
        getServer().getPluginManager().registerEvents(this, this);

        // Crossword folder
        getDataFolder().mkdirs();
        File crosswordsFolder = new File(getDataFolder(), "crosswords");
        crosswordsFolder.mkdirs();

        loadPlacements();
        registerCommands();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info(" $$$ Craftworld End");

        savePlacements();
        activePlacements.forEach(CrosswordPlacement::stopTicking);
    }

    private static final ResourcePackInfo PACK_INFO = ResourcePackInfo.resourcePackInfo()
            .uri(URI.create("https://download.mc-packs.net/pack/55a3452177f93890f0982ade46da000c0dade571.zip"))
            .hash("55a3452177f93890f0982ade46da000c0dade571")
            .build();

    public void sendResourcePack(final @NonNull Audience target) {
        final ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(PACK_INFO)
                .prompt(Component.text("Please download this resource pack to use Craftword!"))
                .required(true)
                .build();
        target.sendResourcePacks(request);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendResourcePack(event.getPlayer());
    }

    private void registerCommands() {
        LiteralCommandNode<CommandSourceStack> remove = Commands.literal("remove")
                .then(Commands.argument("dimension", ArgumentTypes.world())
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .executes(ctx -> {
                                    World world = ctx.getArgument("dimension", World.class);
                                    BlockPositionResolver posResolver = ctx.getArgument("pos", BlockPositionResolver.class);
                                    BlockPosition pos = posResolver.resolve(ctx.getSource());
                                    Location location = new Location(world, pos.blockX(), pos.blockY(), pos.blockZ());

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

        LiteralCommandNode<CommandSourceStack> fetch = (Commands.literal("fetch")
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

        LiteralCommandNode<CommandSourceStack> fetchRaw = (Commands.literal("fetch_raw")
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
                            fetchRawCrossword(source, site);
                            return Command.SINGLE_SUCCESS;
                        })))
                .build();

        LiteralCommandNode<CommandSourceStack> buildNew = (Commands.literal("build_new")
                .then(Commands.argument("Site", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("Universal");
                            builder.suggest("USAToday");
                            builder.suggest("WashingtonPost");
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                            .executes(ctx -> {
                                CommandSourceStack source = ctx.getSource();
                                String site = ctx.getArgument("Site", String.class);
                                BlockPositionResolver posResolver = ctx.getArgument("pos", BlockPositionResolver.class);
                                BlockPosition position = posResolver.resolve(ctx.getSource());

                                // Get world from the executor's location
                                Entity executor = source.getExecutor();
                                if (executor == null) {
                                    source.getSender().sendMessage(Component.text("Must be run by an entity"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                World world = executor.getWorld();

                                fetchCrossword(source, site, crossword -> {
                                    CrosswordPlacement placement = new CrosswordPlacement(crossword, position, world);
                                    activePlacements.add(placement);
                                    placement.startTicking(this);
                                    placement.generate();
                                });
                                return Command.SINGLE_SUCCESS;
                            }))))
                .build();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register(fetch);
            commands.registrar().register(fetchRaw);
            commands.registrar().register(buildNew);
            commands.registrar().register(remove);
        });
    }

    private void fetchCrossword(CommandSourceStack source, String site, String date, Consumer<Crossword> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String urlString = siteURLString(site, date);
                HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Craftword/1.0");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Crossword crossword = switch (site) {
                    case "Universal" -> CrosswordParser.parseUniversal(site, date, response.toString());
                    case "USAToday" -> CrosswordParser.parseUSAToday(site, date, response.toString());
                    case "WashingtonPost" -> CrosswordParser.parseWaPo(site, date, response.toString());
                    default -> throw new IllegalStateException("Unknown site: " + site);
                };

                File crosswordsFolder = new File(this.getDataFolder(), "crosswords");
                File outputFile = new File(crosswordsFolder, site.toLowerCase() + "-" + date + ".json");
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
        });
    }

    private void fetchCrossword(CommandSourceStack source, String site, Consumer<Crossword> callback) {
        fetchCrossword(source, site, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), callback);
    }

    private void fetchRawCrossword(CommandSourceStack source, String site) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // URL creation
                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String urlString = siteURLString(site, date);
                HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Craftword/1.0");

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
                        Component.text("Fetched Universal crossword for " + date)
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
            case "USAToday" -> ".xml";
            default -> throw new IllegalStateException("Unexpected crossword source: " + site);
        };
    }


    private String siteURLString(String site, String date) {
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
            default -> throw new IllegalStateException("Unexpected crossword source: " + site);
        };
    }

    private record PlacementData(String crossword_date, String crossword_site, String world, int x, int y, int z, String id) {}

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
                        p.getId().toString()
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
                CrosswordPlacement placement = new CrosswordPlacement(crossword, position, world, UUID.fromString(data.id()));
                placement.startTicking(this);
                activePlacements.add(placement);
            }
            getLogger().info("Loaded " + activePlacements.size() + " crossword placements.");
        } catch (Exception e) {
            getLogger().severe("Failed to load placements: " + e.getMessage());
        }
    }
}


