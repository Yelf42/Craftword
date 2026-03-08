package yelf42.craftword;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.NonNull;

import java.net.URI;

public class CraftwordListeners implements Listener {

    private final Craftword craftword = Craftword.getPlugin(Craftword.class);

    private static final ResourcePackInfo PACK_INFO = ResourcePackInfo.resourcePackInfo()
            .uri(URI.create("https://download.mc-packs.net/pack/af8b1259a67e2f4bdc7a4723d0734577bd423ff6.zip"))
            .hash("af8b1259a67e2f4bdc7a4723d0734577bd423ff6")
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

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();
        if (craftword.withinPlacement(location, true) != null || isLetterItem(event.getItemInHand())) event.setCancelled(true);

    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        if (craftword.withinPlacement(location, true) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteractWithItemFrame(PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        CrosswordPlacement crosswordPlacement = craftword.withinPlacement(frame);
        if (crosswordPlacement == null) return;
        event.setCancelled(true);
        if (frame.getWorld().getBlockAt(frame.getLocation().add(0, -1, 0)).getType() == Material.LIME_CONCRETE) return;

        switch (event.getAction()) {
            case PLACE, ROTATE:
                ItemStack itemInHand = event.getPlayer().getInventory().getItemInMainHand();
                if (!isLetterItem(itemInHand)) return;
                crosswordPlacement.modifyCurrentGrid(itemInHand, frame.getLocation());
                frame.setItem(itemInHand.clone());
                break;
            case REMOVE:
                crosswordPlacement.modifyCurrentGrid('.', frame.getLocation());
                frame.setItem(new ItemStack(Material.AIR));
                break;
            default:
                return;
        }
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent event) {
        if (craftword.withinPlacement(event.getEntity().getLocation(), true) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onItemFrameAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;

        CrosswordPlacement crosswordPlacement = craftword.withinPlacement(frame);
        if (crosswordPlacement == null) return;

        if (!frame.getScoreboardTags().contains(crosswordPlacement.getTag())) return;

        crosswordPlacement.modifyCurrentGrid(frame.getItem(), frame.getLocation());
    }

    public boolean isLetterItem(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return false;
        }

        NamespacedKey model = item.getItemMeta().getItemModel();
        return model != null &&
                model.toString().startsWith("craftword:letter_");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.POLISHED_BLACKSTONE_BUTTON) {
            CrosswordPlacement placement = craftword.withinPlacement(event.getInteractionPoint().add(1, 0 , 1));
            if (placement != null) {
                placement.addHintRequest(event.getPlayer().getUniqueId());
                return;
            }
        }

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (item.getType() != Material.WRITTEN_BOOK) {
            // Stop placing item-frames in placement
            if (craftword.withinPlacement(event.getInteractionPoint(), true) != null) event.setCancelled(true);
            return;
        }

        BookMeta bookMeta = (BookMeta) item.getItemMeta();
        if (!"Alphabet".equals(bookMeta.getTitle())) return;

        event.setCancelled(true);

        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Crossword Alphabet"));

        for (char c = 'a'; c <= 'z'; c++) {
            ItemStack letterItem = new ItemStack(Material.STRUCTURE_VOID);
            ItemMeta letterMeta = letterItem.getItemMeta();
            letterMeta.setMaxStackSize(1);
            letterMeta.displayName(Component.text((""+c).toUpperCase()));
            letterMeta.setItemModel(NamespacedKey.fromString("craftword:letter_" + c));
            letterItem.setItemMeta(letterMeta);
            inv.addItem(letterItem);
        }

        event.getPlayer().openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(Component.text("Crossword Alphabet"))) return;

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            ItemStack held = event.getWhoClicked().getItemOnCursor();

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) {
                return;
            }

            if (event.isShiftClick()) {
                event.getWhoClicked().getInventory().addItem(clicked.clone());
                return;
            }

            if (!held.isEmpty() && !isLetterItem(held)) {
                event.getWhoClicked().getInventory().addItem(held.clone());
            }
            event.getWhoClicked().setItemOnCursor(clicked.clone());
        } else {
            if (event.isShiftClick()) {
                if (isLetterItem(event.getCurrentItem())) event.setCurrentItem(null);
                event.setCancelled(true);
            }
        }
    }

    // Prevent them from putting items into it
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getView().title().equals(Component.text("Crossword Alphabet"))) return;
        event.setCancelled(true);
    }

}
