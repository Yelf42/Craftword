package yelf42.craftword;

import io.papermc.paper.math.BlockPosition;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;


// TODO store current placed letters (passed from onPlayerInteract),
//  store number of placed letters to compare to total letters,
//  check answer if num letters matches max
//  regen on plugin onEnable
public class CrosswordPlacement {

    private final Crossword crossword;
    private final BlockPosition position;
    private final World world;
    private BukkitTask tickTask;

    private final UUID id;
    private final String tag;

    private BoundingBox boundingBox;

    public CrosswordPlacement(Crossword crossword, BlockPosition position, World world) {
        this.crossword = crossword;
        this.position = position;
        this.world = world;

        this.id = UUID.randomUUID();
        this.tag = id.toString().replace("-", "");

        this.boundingBox = new BoundingBox(position.blockX(), position.blockY() - 1, position.blockZ(),
                position.blockX() + crossword.width(), position.blockY() + 3, position.blockZ() + crossword.height());
    }

    public CrosswordPlacement(Crossword crossword, BlockPosition position, World world, UUID id) {
        this.crossword = crossword;
        this.position = position;
        this.world = world;

        this.id = id;
        this.tag = id.toString().replace("-", "");

        this.boundingBox = new BoundingBox(position.blockX(), position.blockY() - 1, position.blockZ(),
                position.blockX() + crossword.width(), position.blockY() + 3, position.blockZ() + crossword.height());
    }

    public Crossword getCrossword() {
        return crossword;
    }

    public BlockPosition getPosition() {
        return position;
    }

    public World getWorld() {
        return world;
    }

    public UUID getId() {
        return id;
    }

    private String getTag() {
        return "\"" + tag + "\""; //id.toString().replace("-", "");
    }

    // Called the first time to build the visual elements in the world
    // Generates SOUTH-EAST, starting at [0,0]
    public void generate() {
        Set<Integer> seenClues = new HashSet<>();
        seenClues.add(-1);
        for (int row = 0; row < crossword.height(); row++) {
            for (int col = 0; col < crossword.width(); col++) {
                char cell = crossword.grid()[row][col];
                BlockData toPlace = (cell == '.') ? Material.BLACK_CONCRETE.createBlockData() : Material.WHITE_CONCRETE.createBlockData();
                world.setBlockData(position.blockX() + col, position.blockY() - 1, position.blockZ() + row, toPlace);

                if (cell != '.') {
                    spawnGridLines(position.blockX() + col + 1.0f, position.blockY(), position.blockZ() + row + 1.0f);
                    placeLetterFrame(position.blockX() + col, position.blockY(), position.blockZ() + row);
                }

                int across = crossword.acrossIndex()[row][col];
                int down = crossword.downIndex()[row][col];
                if (!seenClues.contains(across)) {
                    spawnNumber(position.blockX() + col, position.blockY(), position.blockZ() + row, across);
                    seenClues.add(across);
                } else if (!seenClues.contains(down)) {
                    spawnNumber(position.blockX() + col, position.blockY(), position.blockZ() + row, down);
                    seenClues.add(down);
                }
            }
        }

        spawnMetadata(position.blockX() + crossword.height() / 2.0F, position.blockY() + 0.5F, position.blockZ() - 2.0F);
    }

    // Starts the 3-tick update loop
    public void startTicking(Craftword plugin) {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 5L);
    }

    // Stops the update loop
    public void stopTicking() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void tick() {
        // Display clues
        world.getNearbyEntities(this.boundingBox).forEach(entity -> {
            if (entity instanceof Player player) {
                int col = player.getLocation().getBlockX() - position.blockX();
                int row = player.getLocation().getBlockZ() - position.blockZ();

                // Check bounds
                if (row < 0 || row >= crossword.height() || col < 0 || col >= crossword.width()) return;

                int across = crossword.acrossIndex()[row][col];
                int down = crossword.downIndex()[row][col];

                if (across > 0 && down > 0) {
                    if (player.isSneaking()) {
                        player.sendActionBar(wrappedText(down + " Down: " + crossword.downClues().get(down).text(), NamedTextColor.BLUE));
                    } else {
                        player.sendActionBar(wrappedText(across + " Across: " + crossword.acrossClues().get(across).text(), NamedTextColor.RED));
                    }
                } else if (across > 0) {
                    player.sendActionBar(wrappedText(across + " Across: " + crossword.acrossClues().get(across).text(), NamedTextColor.RED));
                } else if (down > 0) {
                    player.sendActionBar(wrappedText(down + " Down: " + crossword.downClues().get(down).text(), NamedTextColor.BLUE));
                }
            }
        });
    }

    private static final Key TOP_FONT = Key.key("craftword", "monocraft_shifted_up");
    private static final Key CENTRE_FONT = Key.key("craftword", "monocraft");
    private static final Key BOTTOM_FONT = Key.key("craftword", "monocraft_shifted_down");
    private static final int MAX_LENGTH = 50;
    private static final int MIN_SECOND_LINE_WORDS = 3;

    public static @NotNull TextComponent wrappedText(String text, NamedTextColor color) {
        if (text.length() <= MAX_LENGTH) {
            return Component.text(text, color).font(TOP_FONT);
        }

        String[] words = text.split(" ");

        int bestSplit1 = -1;
        for (int i = 0; i < words.length - MIN_SECOND_LINE_WORDS; i++) {
            String line1 = String.join(" ", Arrays.copyOfRange(words, 0, i + 1));
            String remaining = String.join(" ", Arrays.copyOfRange(words, i + 1, words.length));

            if (line1.length() > MAX_LENGTH) break;

            if (remaining.split(" ").length >= MIN_SECOND_LINE_WORDS) {
                bestSplit1 = i;
            }
        }

        if (bestSplit1 == -1) {
            return Component.text(text, color).font(TOP_FONT);
        }

        String line1Text = String.join(" ", Arrays.copyOfRange(words, 0, bestSplit1 + 1));
        String remaining = String.join(" ", Arrays.copyOfRange(words, bestSplit1 + 1, words.length));

        if (remaining.length() <= MAX_LENGTH) {
            return twoLines(line1Text, remaining, color);
        }

        String[] remainingWords = remaining.split(" ");
        int bestSplit2 = -1;
        for (int i = 0; i < remainingWords.length - MIN_SECOND_LINE_WORDS; i++) {
            String line2 = String.join(" ", Arrays.copyOfRange(remainingWords, 0, i + 1));
            String line3 = String.join(" ", Arrays.copyOfRange(remainingWords, i + 1, remainingWords.length));

            if (line2.length() > MAX_LENGTH) break;

            if (line3.split(" ").length >= MIN_SECOND_LINE_WORDS) {
                bestSplit2 = i;
            }
        }

        if (bestSplit2 == -1) {
            String[] line2Words = remaining.split(" ");
            int cutoff = line2Words.length;
            for (int i = MIN_SECOND_LINE_WORDS; i < line2Words.length; i++) {
                String candidate = String.join(" ", Arrays.copyOfRange(line2Words, 0, i + 1));
                if (candidate.length() > MAX_LENGTH) {
                    cutoff = i;
                    break;
                }
            }
            String line2Text = String.join(" ", Arrays.copyOfRange(line2Words, 0, cutoff));
            return twoLines(line1Text, line2Text, color);
        }

        String line2Text = String.join(" ", Arrays.copyOfRange(remainingWords, 0, bestSplit2 + 1));
        String line3Text = String.join(" ", Arrays.copyOfRange(remainingWords, bestSplit2 + 1, remainingWords.length));

        if (line3Text.length() > MAX_LENGTH) {
            String[] line3Words = line3Text.split(" ");
            int cutoff = line3Words.length;
            for (int i = MIN_SECOND_LINE_WORDS; i < line3Words.length; i++) {
                String candidate = String.join(" ", Arrays.copyOfRange(line3Words, 0, i + 1));
                if (candidate.length() > MAX_LENGTH) {
                    cutoff = i;
                    break;
                }
            }
            line3Text = String.join(" ", Arrays.copyOfRange(line3Words, 0, cutoff));
        }

        int negSpaceWidth = (int) (MAX_LENGTH * 7.5f);

        int line1Pad = MAX_LENGTH - line1Text.length();
        int line2Pad = MAX_LENGTH - line2Text.length();
        int line3Pad = MAX_LENGTH - line3Text.length();

        String line1Padded = " ".repeat(line1Pad / 2) + line1Text + " ".repeat(line1Pad - line1Pad / 2);
        String line2Padded = " ".repeat(line2Pad / 2) + line2Text + " ".repeat(line2Pad - line2Pad / 2);
        String line3Padded = " ".repeat(line3Pad / 2) + line3Text + " ".repeat(line3Pad - line3Pad / 2);

        return Component.text()
                .append(Component.text(line1Padded, color).font(TOP_FONT))
                .append(Component.translatable("space.-" + negSpaceWidth))
                .append(Component.text(line2Padded, color).font(CENTRE_FONT))
                .append(Component.translatable("space.-" + negSpaceWidth))
                .append(Component.text(line3Padded, color).font(BOTTOM_FONT))
                .build();
    }

    private static @NotNull TextComponent twoLines(String line1Text, String line2Text, NamedTextColor color) {
        int negSpaceWidth = (int) (MAX_LENGTH * 7.5f);

        int line1Pad = MAX_LENGTH - line1Text.length();
        int line2Pad = MAX_LENGTH - line2Text.length();

        String line1Padded = " ".repeat(line1Pad / 2) + line1Text + " ".repeat(line1Pad - line1Pad / 2);
        String line2Padded = " ".repeat(line2Pad / 2) + line2Text + " ".repeat(line2Pad - line2Pad / 2);

        return Component.text()
                .append(Component.text(line1Padded, color).font(TOP_FONT))
                .append(Component.translatable("space.-" + negSpaceWidth))
                .append(Component.text(line2Padded, color).font(CENTRE_FONT))
                .build();
    }

    public boolean isInsideGrid(Location location) {
        return (location.getWorld() == this.world && this.boundingBox.contains(location.getX(),location.getY(),location.getZ()));
    }

    public boolean isInsideGrid(Entity entity) {
        return (entity.getWorld() == this.world && this.boundingBox.contains(entity.getX(),entity.getY(),entity.getZ()));
    }

    public void removeTextDisplays() {
        world.getEntities().forEach(entity -> {
            if (entity.getScoreboardTags().contains(this.tag)) {
                entity.remove();
            }
        });
    }

    private static final Transformation TRANSFORM_E = new Transformation(
            new Vector3f(-0.035f, -0.6f, 0.0f),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(0.5f, 8.0f, 1.0f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    private static final Transformation TRANSFORM_W = new Transformation(
            new Vector3f(-0.975f, -0.6f, 0.0f),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(0.5f, 8.0f, 1.0f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    private static final Transformation TRANSFORM_S = new Transformation(
            new Vector3f(0.6f, 0.025f, 0.0f),
            new AxisAngle4f((float) Math.PI / 2, 0, 0, 1),
            new Vector3f(0.5f, 8.0f, 1.0f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    private static final Transformation TRANSFORM_N = new Transformation(
            new Vector3f(0.6f, 0.965f, 0.0f),
            new AxisAngle4f((float) Math.PI / 2, 0, 0, 1),
            new Vector3f(0.5f, 8.0f, 1.0f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    private void spawnGridLines(float x, float y, float z) {
        Location location = new Location(world, x, y, z);
        for (Transformation transform : new Transformation[]{TRANSFORM_E, TRANSFORM_W, TRANSFORM_S, TRANSFORM_N}) {
            world.spawn(location, TextDisplay.class, display -> {
                display.text(Component.text("■").color(NamedTextColor.BLACK));
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.setShadowed(false);
                display.setSeeThrough(false);
                display.setLineWidth(200);
                display.setTransformation(transform);
                display.setRotation(0.0f, -90.0f);
                display.addScoreboardTag(id.toString().replace("-", ""));
                display.setPersistent(true);
            });
        }
    }

    private static final Transformation TRANSFORM_CLUE = new Transformation(
            new Vector3f(0.3f, 0.6f, 0.0f),
            new AxisAngle4f((float) Math.PI / 2, 0.0f, 0.0f, 1.0f),
            new Vector3f(1.0f, 1.0f, 1.0f),
            new AxisAngle4f((float) -Math.PI / 2, 0.0f, 0.0f, 1.0f)
    );

    private void spawnNumber(float x, float y, float z, int clue) {
        Location location = new Location(world, x, y, z + 1);
        world.spawn(location, TextDisplay.class, display -> {
            display.text(Component.text(String.format("%-3s", clue)).color(NamedTextColor.BLACK));
            display.setAlignment(TextDisplay.TextAlignment.LEFT);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setShadowed(false);
            display.setSeeThrough(false);
            display.setLineWidth(200);
            display.setTransformation(TRANSFORM_CLUE);
            display.setRotation(0.0f, -90.0f);
            display.addScoreboardTag(id.toString().replace("-", ""));
            display.setPersistent(true);
        });
    }

    private void spawnMetadata(float x, float y, float z) {
        Location location = new Location(world, x, y, z + 1);
        world.spawn(location, TextDisplay.class, display -> {
            display.text(Component.text()
                    .append(Component.text(crossword.title()).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.text(crossword.author()).color(NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(crossword.site() + " : " + crossword.date()).color(NamedTextColor.GRAY))
                    .build());
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            //display.setDefaultBackground(false);
            //display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setShadowed(false);
            display.setSeeThrough(false);
            display.setLineWidth(200);
            display.setTransformation(TRANSFORM_CLUE);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.addScoreboardTag(id.toString().replace("-", ""));
            display.setPersistent(true);
        });
    }

    public void placeLetterFrame(double x, double y, double z) {
        Location location = new Location(world, x, y, z);
        ItemFrame frame = world.spawn(location, ItemFrame.class);

        frame.setFacingDirection(BlockFace.UP);
        frame.setVisible(false);

        frame.addScoreboardTag(id.toString().replace("-", ""));
    }
}
