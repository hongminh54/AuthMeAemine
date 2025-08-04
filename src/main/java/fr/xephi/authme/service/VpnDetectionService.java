package fr.xephi.authme.service;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.util.InternetProtocolUtils;

import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class VpnDetectionService {

    private static final ConsoleLogger logger = ConsoleLoggerFactory.get(VpnDetectionService.class);

    @Inject
    private Settings settings;

    private final ConcurrentHashMap<String, CachedVpnResult> vpnCache = new ConcurrentHashMap<>();
    private static final long VPN_CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(30);

    private static final Set<String> KNOWN_VPN_RANGES = new HashSet<>(Arrays.asList(
        "103.28.54.0/24", "103.28.55.0/24", "104.16.0.0/12", "104.17.0.0/16",
        "104.18.0.0/16", "104.19.0.0/16", "104.20.0.0/16", "104.21.0.0/16",
        "185.220.100.0/22", "185.220.101.0/24", "185.220.102.0/24",
        "192.42.116.0/22", "199.87.154.0/24", "209.141.32.0/19"
    ));

    private static final Set<String> DNS_VPN_RANGES = new HashSet<>(Arrays.asList(
        "1.1.1.0/24", "1.0.0.0/24", "8.8.8.0/24", "8.8.4.0/24",
        "9.9.9.0/24", "149.112.112.0/24", "208.67.222.0/24", "208.67.220.0/24",
        "76.76.19.0/24", "76.76.76.0/24", "94.140.14.0/24", "94.140.15.0/24"
    ));

    private static final Set<String> HOSTING_PROVIDERS = new HashSet<>(Arrays.asList(
        "amazonaws.com", "googleusercontent.com", "digitalocean.com",
        "vultr.com", "linode.com", "ovh.net", "hetzner.de", "cloudflare.com"
    ));

    public enum VpnDetectionAction {
        KICK, BLOCK_REGISTER, BLOCK_LOGIN, LOG_ONLY
    }

    private static class CachedVpnResult {
        final boolean isVpn;
        final long timestamp;

        CachedVpnResult(boolean isVpn) {
            this.isVpn = isVpn;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > VPN_CACHE_DURATION_MS;
        }
    }

    public boolean isVpnDetectionEnabled() {
        return settings.getProperty(RestrictionSettings.ENABLE_VPN_DETECTION);
    }

    public VpnDetectionAction getVpnDetectionAction() {
        String action = settings.getProperty(RestrictionSettings.VPN_DETECTION_ACTION);
        try {
            return VpnDetectionAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid VPN detection action: " + action + ", using KICK as default");
            return VpnDetectionAction.KICK;
        }
    }

    public boolean isVpnOrProxy(String ip) {
        if (!isVpnDetectionEnabled() || ip == null || InternetProtocolUtils.isLocalAddress(ip)) {
            return false;
        }

        if (isWhitelisted(ip)) {
            return false;
        }

        CachedVpnResult cached = vpnCache.get(ip);
        if (cached != null && !cached.isExpired()) {
            return cached.isVpn;
        }

        boolean isVpn = performVpnCheck(ip);
        vpnCache.put(ip, new CachedVpnResult(isVpn));

        if (isVpn) {
            logger.info("VPN/Proxy detected for IP: " + ip);
        }

        return isVpn;
    }

    private boolean performVpnCheck(String ip) {
        try {
            if (isInKnownVpnRange(ip)) {
                return true;
            }

            if (settings.getProperty(RestrictionSettings.ENABLE_DNS_VPN_DETECTION) && isInDnsVpnRange(ip)) {
                return true;
            }

            if (isInCustomVpnRange(ip)) {
                return true;
            }

            if (settings.getProperty(RestrictionSettings.ENABLE_ADVANCED_VPN_DETECTION)) {
                InetAddress address = InetAddress.getByName(ip);
                String hostname = getHostname(address);

                if (hostname != null && (isHostingProvider(hostname) || isCustomVpnHostname(hostname))) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            logger.debug("Error checking VPN for IP " + ip + ": " + e.getMessage());
            return false;
        }
    }

    private boolean isInKnownVpnRange(String ip) {
        for (String range : KNOWN_VPN_RANGES) {
            if (isIpInRange(ip, range)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInDnsVpnRange(String ip) {
        for (String range : DNS_VPN_RANGES) {
            if (isIpInRange(ip, range)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInCustomVpnRange(String ip) {
        Set<String> customRanges = settings.getProperty(RestrictionSettings.CUSTOM_VPN_RANGES);
        for (String range : customRanges) {
            if (isIpInRange(ip, range)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhitelisted(String ip) {
        Set<String> whitelist = settings.getProperty(RestrictionSettings.VPN_WHITELIST);
        for (String range : whitelist) {
            if (isIpInRange(ip, range)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIpInRange(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress targetAddr = InetAddress.getByName(ip);
            InetAddress rangeAddr = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] targetBytes = targetAddr.getAddress();
            byte[] rangeBytes = rangeAddr.getAddress();

            if (targetBytes.length != rangeBytes.length) {
                return false;
            }

            int bytesToCheck = prefixLength / 8;
            int bitsToCheck = prefixLength % 8;

            for (int i = 0; i < bytesToCheck; i++) {
                if (targetBytes[i] != rangeBytes[i]) {
                    return false;
                }
            }

            if (bitsToCheck > 0 && bytesToCheck < targetBytes.length) {
                int mask = 0xFF << (8 - bitsToCheck);
                return (targetBytes[bytesToCheck] & mask) == (rangeBytes[bytesToCheck] & mask);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getHostname(InetAddress address) {
        try {
            return address.getCanonicalHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isHostingProvider(String hostname) {
        if (hostname == null) {
            return false;
        }

        String lowerHostname = hostname.toLowerCase();
        return HOSTING_PROVIDERS.stream().anyMatch(lowerHostname::contains);
    }

    private boolean isCustomVpnHostname(String hostname) {
        if (hostname == null) {
            return false;
        }

        Set<String> customHostnames = settings.getProperty(RestrictionSettings.CUSTOM_VPN_HOSTNAMES);
        String lowerHostname = hostname.toLowerCase();
        return customHostnames.stream().anyMatch(lowerHostname::contains);
    }

    public void clearCache() {
        vpnCache.clear();
        logger.info("VPN detection cache cleared");
    }

    public void clearCacheForIp(String ip) {
        vpnCache.remove(ip);
        logger.debug("VPN cache cleared for IP: " + ip);
    }

    public String getVpnDetectionInfo(String ip) {
        if (!isVpnDetectionEnabled()) {
            return "VPN detection is disabled";
        }

        if (isWhitelisted(ip)) {
            return "IP is whitelisted";
        }

        if (isInKnownVpnRange(ip)) {
            return "Detected in known VPN ranges";
        }

        if (settings.getProperty(RestrictionSettings.ENABLE_DNS_VPN_DETECTION) && isInDnsVpnRange(ip)) {
            return "Detected as DNS VPN service";
        }

        if (isInCustomVpnRange(ip)) {
            return "Detected in custom VPN ranges";
        }

        try {
            if (settings.getProperty(RestrictionSettings.ENABLE_ADVANCED_VPN_DETECTION)) {
                InetAddress address = InetAddress.getByName(ip);
                String hostname = getHostname(address);

                if (hostname != null && isHostingProvider(hostname)) {
                    return "Detected as hosting provider: " + hostname;
                }

                if (hostname != null && isCustomVpnHostname(hostname)) {
                    return "Detected as custom VPN hostname: " + hostname;
                }
            }
        } catch (Exception e) {
            return "Error during detection: " + e.getMessage();
        }

        return "Not detected as VPN/Proxy";
    }
}
