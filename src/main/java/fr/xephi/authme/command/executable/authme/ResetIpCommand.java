package fr.xephi.authme.command.executable.authme;

import fr.xephi.authme.command.ExecutableCommand;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.IpRestrictionService;
import fr.xephi.authme.util.PlayerUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Admin command to reset IP cache and restrictions for a specific IP or player.
 */
public class ResetIpCommand implements ExecutableCommand {

    @Inject
    private BukkitService bukkitService;

    @Inject
    private CommonService commonService;

    @Inject
    private IpRestrictionService ipRestrictionService;

    @Inject
    private DataSource dataSource;

    @Override
    public void executeCommand(CommandSender sender, List<String> arguments) {
        if (arguments.isEmpty()) {
            // Reset all IP cache and database
            ipRestrictionService.clearAllCache();
            int clearedAccounts = dataSource.clearAllLastIp();
            commonService.send(sender, MessageKey.IP_CACHE_CLEARED);
            sender.sendMessage("§aCleared IP data from " + clearedAccounts + " accounts in database.");
            return;
        }

        String target = arguments.get(0);
        String ipToReset = null;

        // Check if target is a player name or IP address
        Player player = bukkitService.getPlayerExact(target);
        if (player != null) {
            // Target is an online player
            ipToReset = PlayerUtils.getPlayerIp(player);
            if (ipToReset == null) {
                sender.sendMessage("§cCould not determine IP address for player " + target);
                return;
            }
        } else if (isValidIpAddress(target)) {
            // Target is an IP address
            ipToReset = target;
        } else {
            // Target might be an offline player, try to get IP from database
            sender.sendMessage("§cPlayer " + target + " is not online. Please provide an IP address instead.");
            return;
        }

        // Reset cache and database for the specific IP
        ipRestrictionService.invalidateCache(ipToReset);
        int clearedAccounts = dataSource.clearLastIpForIp(ipToReset);
        commonService.send(sender, MessageKey.IP_CACHE_RESET, ipToReset);

        sender.sendMessage("§aIP reset completed for: " + ipToReset);
        sender.sendMessage("§7Cache cleared and IP data removed from " + clearedAccounts + " accounts in database.");
    }

    /**
     * Simple validation to check if a string looks like an IP address.
     *
     * @param ip the string to check
     * @return true if it looks like an IP address
     */
    private boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
