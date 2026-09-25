package dev.magnet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** The collector item. It is recognised by a tag on the item, so renaming it in an anvil does not break it. */
public final class Items {

    private final MagnetPlugin plugin;
    private final NamespacedKey tag;

    Items(MagnetPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "collector");
    }

    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(plugin.settings().block, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(plugin.messages().parse(plugin.getConfig().getString("item.name", "<green>Chunk Collector"))));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("item.lore")) lore.add(noItalic(plugin.messages().parse(line)));
        meta.lore(lore);

        if (plugin.getConfig().getBoolean("item.glow", true)) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCollector(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(tag);
    }

    static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
