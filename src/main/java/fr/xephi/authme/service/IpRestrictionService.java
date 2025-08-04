package fr.xephi.authme.service;

import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.initialization.Reloadable;
import fr.xephi.authme.permission.PermissionsManager;
import fr.xephi.authme.permission.PlayerStatePermission;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.util.InternetProtocolUtils;
import fr.xephi.authme.util.PlayerUtils;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling IP-based restrictions with improved performance and caching.
 */
public class IpRestrictionService implements Reloadable {

    @Inject
    private DataSource dataSource;

    @Inject
    private Settings settings;

    @Inject
    private PermissionsManager permissionsManager;

    @Inject
    private BukkitService bukkitService;

    private final ConcurrentHashMap<String, CachedIpData> ipCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastCheckTime = new ConcurrentHashMap<>();

    private static final long CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 60L * 5L; // 5 minutes

    private BukkitTask cleanupTask;

    /**
     * Cached data for an IP address.
     */
    private static class CachedIpData {
        final int registeredCount;
        final int onlineCount;
        final long timestamp;

        CachedIpData(int registeredCount, int onlineCount) {
            this.registeredCount = registeredCount;
            this.onlineCount = onlineCount;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
        }
    }

    /**
     * Checks if the player's IP is allowed to register based on the maximum registrations per IP.
     *
     * @param player the player to check
     * @return true if registration is allowed, false otherwise
     */
    public boolean isIpAllowedToRegister(Player player) {
        int maxRegPerIp = settings.getProperty(RestrictionSettings.MAX_REGISTRATION_PER_IP);
        if (maxRegPerIp <= 0) {
            return true;
        }

        String ip = PlayerUtils.getPlayerIp(player);
        if (ip == null || InternetProtocolUtils.isLoopbackAddress(ip)) {
            return true;
        }

        if (permissionsManager.hasPermission(player, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS)) {
            return true;
        }

        int registeredCount = getRegisteredAccountsCount(ip);
        return registeredCount < maxRegPerIp;
    }

    /**
     * Checks if the player's IP has reached the maximum number of logged-in players.
     *
     * @param player the player to check
     * @return true if the limit has been reached, false otherwise
     */
    public boolean hasReachedMaxLoggedInPlayersForIp(Player player) {
        int maxLoginPerIp = settings.getProperty(RestrictionSettings.MAX_LOGIN_PER_IP);
        if (maxLoginPerIp <= 0) {
            return false;
        }

        String ip = PlayerUtils.getPlayerIp(player);
        if (ip == null || InternetProtocolUtils.isLoopbackAddress(ip)) {
            return false;
        }

        if (permissionsManager.hasPermission(player, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS)) {
            return false;
        }

        int loggedInCount = getLoggedInPlayersCount(ip, player.getName());
        return loggedInCount >= maxLoginPerIp;
    }

    /**
     * Checks if the player's IP has reached the maximum number of joined players.
     *
     * @param player the player to check
     * @return true if the limit has been reached, false otherwise
     */
    public boolean hasReachedMaxJoinedPlayersForIp(Player player) {
        int maxJoinPerIp = settings.getProperty(RestrictionSettings.MAX_JOIN_PER_IP);
        if (maxJoinPerIp <= 0) {
            return false;
        }

        String ip = PlayerUtils.getPlayerIp(player);
        if (ip == null || InternetProtocolUtils.isLoopbackAddress(ip)) {
            return false;
        }

        if (permissionsManager.hasPermission(player, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS)) {
            return false;
        }

        int onlineCount = getOnlinePlayersCount(ip);
        return onlineCount > maxJoinPerIp;
    }

    /**
     * Gets the number of registered accounts for the given IP address.
     *
     * @param ip the IP address
     * @return the number of registered accounts
     */
    public int getRegisteredAccountsCount(String ip) {
        CachedIpData cached = ipCache.get(ip);
        if (cached != null && !cached.isExpired()) {
            return cached.registeredCount;
        }

        List<String> accounts = dataSource.getAllAuthsByIp(ip);
        int count = accounts.size();
        
        updateCache(ip, count, getOnlinePlayersCount(ip));
        return count;
    }

    /**
     * Gets the number of logged-in players for the given IP address.
     *
     * @param ip the IP address
     * @param excludePlayer player name to exclude from count
     * @return the number of logged-in players
     */
    public int getLoggedInPlayersCount(String ip, String excludePlayer) {
        int count = 0;
        for (Player onlinePlayer : bukkitService.getOnlinePlayers()) {
            String playerIp = PlayerUtils.getPlayerIp(onlinePlayer);
            if (ip.equalsIgnoreCase(playerIp)
                && !onlinePlayer.getName().equals(excludePlayer)
                && dataSource.isLogged(onlinePlayer.getName().toLowerCase())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets the number of online players for the given IP address.
     *
     * @param ip the IP address
     * @return the number of online players
     */
    public int getOnlinePlayersCount(String ip) {
        int count = 0;
        for (Player onlinePlayer : bukkitService.getOnlinePlayers()) {
            String playerIp = PlayerUtils.getPlayerIp(onlinePlayer);
            if (ip.equalsIgnoreCase(playerIp)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Updates the cache for the given IP address.
     *
     * @param ip the IP address
     * @param registeredCount the number of registered accounts
     * @param onlineCount the number of online players
     */
    private void updateCache(String ip, int registeredCount, int onlineCount) {
        ipCache.put(ip, new CachedIpData(registeredCount, onlineCount));
    }

    /**
     * Clears expired cache entries.
     */
    public void cleanupCache() {
        ipCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        lastCheckTime.entrySet().removeIf(entry -> 
            System.currentTimeMillis() - entry.getValue() > CACHE_DURATION_MS);
    }

    /**
     * Invalidates cache for the given IP address.
     *
     * @param ip the IP address
     */
    public void invalidateCache(String ip) {
        ipCache.remove(ip);
        lastCheckTime.remove(ip);
    }

    /**
     * Clears all cached IP data.
     */
    public void clearAllCache() {
        ipCache.clear();
        lastCheckTime.clear();
    }

    /**
     * Gets all registered usernames for the given IP address.
     *
     * @param ip the IP address
     * @return list of usernames
     */
    public List<String> getAllAccountsByIp(String ip) {
        return dataSource.getAllAuthsByIp(ip);
    }

    @PostConstruct
    @Override
    public void reload() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        if (settings.getProperty(RestrictionSettings.ENABLE_IMPROVED_IP_RESTRICTION)) {
            cleanupTask = bukkitService.runTaskTimer(new BukkitRunnable() {
                @Override
                public void run() {
                    cleanupCache();
                }
            }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
        }
    }
}
