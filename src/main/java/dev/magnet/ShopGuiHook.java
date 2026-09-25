package dev.magnet;

import net.brcdev.shopgui.ShopGuiPlusApi;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The only class that touches the ShopGUI+ API, so the plugin loads without it. Only created when ShopGUI+ is
 * installed, and only used when something is sold, by then its shops are loaded.
 */
final class ShopGuiHook {

    /** @return what one item sells for, 0 or less if ShopGUI+ has no sell price for it. */
    double price(ItemStack one, OfflinePlayer owner) {
        Player online = owner.getPlayer();
        // The player version takes their permissions and multipliers into account, only possible while they are online.
        return online != null ? ShopGuiPlusApi.getItemStackPriceSell(online, one) : ShopGuiPlusApi.getItemStackPriceSell(one);
    }
}
