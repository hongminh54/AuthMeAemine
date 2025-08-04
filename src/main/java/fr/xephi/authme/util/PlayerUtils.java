package fr.xephi.authme.util;

import org.bukkit.entity.Player;

/**
 * Player utilities.
 */
public final class PlayerUtils {

    // Utility class
    private PlayerUtils() {
    }

    /**
     * Returns the IP of the given player with improved error handling and proxy support.
     *
     * @param player The player to return the IP address for
     * @return The player's IP address, or null if unable to determine
     */
    public static String getPlayerIp(Player player) {
        if (player == null) {
            return null;
        }

        try {
            if (player.getAddress() == null || player.getAddress().getAddress() == null) {
                return null;
            }

            String ip = player.getAddress().getAddress().getHostAddress();

            // Handle X-Forwarded-For header for proxy setups
            if (player.hasMetadata("authme_real_ip")) {
                String realIp = player.getMetadata("authme_real_ip").get(0).asString();
                if (realIp != null && !realIp.isEmpty()) {
                    return realIp;
                }
            }

            return ip;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns if the player is an NPC or not.
     *
     * @param player The player to check
     * @return True if the player is an NPC, false otherwise
     */
    public static boolean isNpc(Player player) {
        return player.hasMetadata("NPC");
    }

}
