package fr.xephi.authme.command.executable.authme;

import fr.xephi.authme.command.ExecutableCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.VpnDetectionService;
import fr.xephi.authme.util.PlayerUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

public class VpnCommand implements ExecutableCommand {

    @Inject
    private VpnDetectionService vpnDetectionService;

    @Inject
    private CommonService commonService;

    @Override
    public void executeCommand(CommandSender sender, List<String> arguments) {
        if (arguments.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /authme vpn <check|info> [player|ip]");
            return;
        }

        String action = arguments.get(0).toLowerCase();
        
        switch (action) {
            case "check":
                handleCheckCommand(sender, arguments);
                break;
            case "info":
                handleInfoCommand(sender, arguments);
                break;
            case "reload":
                handleReloadCommand(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown action. Use: check, info, or reload");
        }
    }

    private void handleCheckCommand(CommandSender sender, List<String> arguments) {
        String target = getTarget(sender, arguments);
        if (target == null) {
            return;
        }

        String ip = getIpFromTarget(target);
        if (ip == null) {
            sender.sendMessage(ChatColor.RED + "Could not determine IP for: " + target);
            return;
        }

        boolean isVpn = vpnDetectionService.isVpnOrProxy(ip);
        String status = isVpn ? "VPN/Proxy detected" : "Clean";

        commonService.send(sender, MessageKey.VPN_CHECK_RESULT, target + " (" + ip + "): " + status);
    }

    private void handleInfoCommand(CommandSender sender, List<String> arguments) {
        String target = getTarget(sender, arguments);
        if (target == null) {
            return;
        }

        String ip = getIpFromTarget(target);
        if (ip == null) {
            sender.sendMessage(ChatColor.RED + "Could not determine IP for: " + target);
            return;
        }

        String info = vpnDetectionService.getVpnDetectionInfo(ip);
        commonService.send(sender, MessageKey.VPN_DETECTION_INFO, target + " (" + ip + "): " + info);
    }

    private void handleReloadCommand(CommandSender sender) {
        vpnDetectionService.clearCache();
        sender.sendMessage(ChatColor.GREEN + "VPN detection cache cleared and reloaded");
    }

    private String getTarget(CommandSender sender, List<String> arguments) {
        if (arguments.size() > 1) {
            return arguments.get(1);
        }
        
        if (sender instanceof Player) {
            return sender.getName();
        }
        
        sender.sendMessage(ChatColor.RED + "Please specify a player name or IP address");
        return null;
    }

    private String getIpFromTarget(String target) {
        Player player = org.bukkit.Bukkit.getPlayerExact(target);
        if (player != null) {
            return PlayerUtils.getPlayerIp(player);
        }
        
        if (target.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return target;
        }
        
        return null;
    }
}
