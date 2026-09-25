package dev.magpie;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The collector window: what is stored, a page at a time, and buttons for the rest. Click an item to take a stack,
 * shift click to take as much as fits.
 */
public final class Menu implements InventoryHolder {

    private static final int ITEM_SLOTS = 45;
    private static final int PREV = 45, INFO = 49, NEXT = 53, SELL = 47, AUTOSELL = 48, WITHDRAW_ALL = 51;

    private final MagpiePlugin plugin;
    private final Collector collector;
    private final Player viewer;
    private final OfflinePlayer owner;
    private final Inventory inventory;
    private final List<Collector.Entry> shown = new ArrayList<>();
    private int page;

    Menu(MagpiePlugin plugin, Collector collector, Player viewer) {
        this.plugin = plugin;
        this.collector = collector;
        this.viewer = viewer;
        this.owner = Bukkit.getOfflinePlayer(collector.owner);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().component("menu-title"));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void open() {
        viewer.openInventory(inventory);
    }

    private String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** Draws the window again. Called when it opens, after a click and every couple of seconds. */
    void render() {
        Messages messages = plugin.messages();
        Sales sales = plugin.sales();

        shown.clear();
        shown.addAll(collector.entries().values());
        shown.sort(Comparator.comparing(e -> e.key.getType().name()));

        int pages = Math.max(1, (shown.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        page = Math.max(0, Math.min(page, pages - 1));
        List<Collector.Entry> slice = shown.subList(page * ITEM_SLOTS, Math.min(shown.size(), (page + 1) * ITEM_SLOTS));

        inventory.clear();
        for (int slot = 0; slot < slice.size(); slot++) inventory.setItem(slot, icon(slice.get(slot)));

        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, null, null);
        for (int slot = ITEM_SLOTS; slot < 54; slot++) inventory.setItem(slot, filler);

        if (page > 0) inventory.setItem(PREV, button(Material.ARROW, "menu-prev", null, "page", String.valueOf(page), "pages", String.valueOf(pages)));
        if (page < pages - 1) inventory.setItem(NEXT, button(Material.ARROW, "menu-next", null, "page", String.valueOf(page + 2), "pages", String.valueOf(pages)));

        String ownerName = owner.getName() == null ? "?" : owner.getName();
        inventory.setItem(INFO, button(Material.BOOK, "menu-info-name", "menu-info-lore",
                "items", count(collector.total()), "types", String.valueOf(collector.entries().size()),
                "owner", ownerName, "page", String.valueOf(page + 1), "pages", String.valueOf(pages)));
        inventory.setItem(WITHDRAW_ALL, button(Material.HOPPER, "menu-withdraw-name", "menu-withdraw-lore"));

        if (sales.enabled()) {
            inventory.setItem(SELL, button(Material.GOLD_INGOT, "menu-sell-name", "menu-sell-lore",
                    "worth", sales.format(sales.worth(collector, owner))));
            inventory.setItem(AUTOSELL, button(collector.autosell ? Material.LIME_DYE : Material.GRAY_DYE,
                    collector.autosell ? "menu-autosell-on" : "menu-autosell-off", "menu-autosell-lore",
                    "seconds", String.valueOf(plugin.settings().autosellSeconds)));
        }
    }

    private ItemStack icon(Collector.Entry entry) {
        Messages messages = plugin.messages();
        ItemStack icon = entry.key.clone();
        icon.setAmount((int) Math.max(1, Math.min(entry.amount, icon.getMaxStackSize())));

        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Items.noItalic(messages.component("item-stored", "amount", count(entry.amount))));

        if (plugin.sales().enabled()) {
            double price = plugin.sales().unitPrice(entry.key, owner);
            if (price > 0) lore.add(Items.noItalic(messages.component("item-worth", "worth", plugin.sales().format(price * entry.amount))));
        }
        for (String line : messages.lines("item-actions")) lore.add(Items.noItalic(messages.parse(line)));

        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack button(Material material, String nameKey, String loreKey, String... pairs) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nameKey == null ? Component.empty() : Items.noItalic(plugin.messages().component(nameKey, pairs)));
        if (loreKey != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.messages().lines(loreKey)) lore.add(Items.noItalic(plugin.messages().parse(line, pairs)));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    void click(int slot, boolean shift) {
        if (slot < ITEM_SLOTS) {
            if (slot < shown.size() - page * ITEM_SLOTS) withdraw(shown.get(page * ITEM_SLOTS + slot), shift);
        } else if (slot == PREV) {
            page--;
        } else if (slot == NEXT) {
            page++;
        } else if (slot == WITHDRAW_ALL) {
            for (Collector.Entry entry : new ArrayList<>(collector.entries().values())) {
                if (!withdraw(entry, true)) break;
            }
        } else if (plugin.sales().enabled() && slot == SELL) {
            plugin.sell(collector, viewer);
        } else if (plugin.sales().enabled() && slot == AUTOSELL) {
            collector.autosell = !collector.autosell;
            collector.dirty = true;
        }
        render();
    }

    /** @return false if the inventory filled up */
    private boolean withdraw(Collector.Entry entry, boolean all) {
        ItemStack key = entry.key;
        long taken = collector.take(entry, all ? entry.amount : Math.min(entry.amount, key.getMaxStackSize()));

        long given = 0;
        boolean full = false;
        for (long left = taken; left > 0 && !full; ) {
            int size = (int) Math.min(left, key.getMaxStackSize());
            ItemStack stack = key.clone();
            stack.setAmount(size);

            var overflow = viewer.getInventory().addItem(stack);
            int back = overflow.isEmpty() ? 0 : overflow.get(0).getAmount();
            given += size - back;
            left -= size;
            full = back > 0;
        }

        collector.restore(entry, taken - given);
        if (full) plugin.messages().send(viewer, "inventory-full");
        return !full;
    }
}
