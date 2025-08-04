package fr.xephi.authme.process.register;

import ch.jalu.injector.factory.SingletonStore;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.AuthMeAsyncPreRegisterEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.AsynchronousProcess;
import fr.xephi.authme.process.register.executors.RegistrationExecutor;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
import fr.xephi.authme.process.register.executors.RegistrationParameters;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.IpRestrictionService;
import fr.xephi.authme.service.VpnDetectionService;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.util.InternetProtocolUtils;
import fr.xephi.authme.util.PlayerUtils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

import static fr.xephi.authme.permission.PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS;

/**
 * Asynchronous processing of a request for registration.
 */
public class AsyncRegister implements AsynchronousProcess {

    @Inject
    private DataSource database;
    @Inject
    private PlayerCache playerCache;
    @Inject
    private BukkitService bukkitService;
    @Inject
    private CommonService service;
    @Inject
    private SingletonStore<RegistrationExecutor> registrationExecutorFactory;
    @Inject
    private IpRestrictionService ipRestrictionService;

    @Inject
    private VpnDetectionService vpnDetectionService;

    AsyncRegister() {
    }

    /**
     * Performs the registration process for the given player.
     *
     * @param variant    the registration method
     * @param parameters the parameters
     * @param <P>        parameters type
     */
    public <P extends RegistrationParameters> void register(RegistrationMethod<P> variant, P parameters) {
        if (preRegisterCheck(variant, parameters.getPlayer())) {
            RegistrationExecutor<P> executor = registrationExecutorFactory.getSingleton(variant.getExecutorClass());
            if (executor.isRegistrationAdmitted(parameters)) {
                executeRegistration(parameters, executor);
            }
        }
    }

    /**
     * Checks if the player is able to register, in that case the {@link AuthMeAsyncPreRegisterEvent} is invoked.
     *
     * @param variant the registration type variant.
     * @param player  the player which is trying to register.
     *
     * @return true if the checks are successful and the event hasn't marked the action as denied, false otherwise.
     */
    private boolean preRegisterCheck(RegistrationMethod<?> variant, Player player) {
        String name = player.getName().toLowerCase(Locale.ROOT);
        if (playerCache.isAuthenticated(name)) {
            service.send(player, MessageKey.ALREADY_LOGGED_IN_ERROR);
            return false;
        } else if (!service.getProperty(RegistrationSettings.IS_ENABLED)) {
            service.send(player, MessageKey.REGISTRATION_DISABLED);
            return false;
        } else if (database.isAuthAvailable(name)) {
            service.send(player, MessageKey.NAME_ALREADY_REGISTERED);
            return false;
        }

        AuthMeAsyncPreRegisterEvent event = bukkitService.createAndCallEvent(
            isAsync -> new AuthMeAsyncPreRegisterEvent(player, isAsync));
        if (!event.canRegister()) {
            return false;
        }

        return variant == RegistrationMethod.API_REGISTRATION || isPlayerIpAllowedToRegister(player);
    }

    /**
     * Executes the registration.
     *
     * @param parameters the registration parameters
     * @param executor   the executor to perform the registration process with
     * @param <P>        registration params type
     */
    private <P extends RegistrationParameters>
    void executeRegistration(P parameters, RegistrationExecutor<P> executor) {
        PlayerAuth auth = executor.buildPlayerAuth(parameters);
        if (database.saveAuth(auth)) {
            executor.executePostPersistAction(parameters);
        } else {
            service.send(parameters.getPlayer(), MessageKey.ERROR);
        }
    }

    /**
     * Checks whether the registration threshold has been exceeded for the given player's IP address.
     *
     * @param player the player to check
     *
     * @return true if registration may take place, false otherwise (IP check failed)
     */
    private boolean isPlayerIpAllowedToRegister(Player player) {
        String ip = PlayerUtils.getPlayerIp(player);

        if (vpnDetectionService.isVpnOrProxy(ip)) {
            VpnDetectionService.VpnDetectionAction action = vpnDetectionService.getVpnDetectionAction();
            if (action == VpnDetectionService.VpnDetectionAction.BLOCK_REGISTER ||
                action == VpnDetectionService.VpnDetectionAction.KICK) {
                service.send(player, MessageKey.VPN_PROXY_DETECTED);
                return false;
            }
        }

        if (service.getProperty(RestrictionSettings.ENABLE_IMPROVED_IP_RESTRICTION)) {
            if (!ipRestrictionService.isIpAllowedToRegister(player)) {
                int maxRegPerIp = service.getProperty(RestrictionSettings.MAX_REGISTRATION_PER_IP);
                List<String> otherAccounts = ipRestrictionService.getAllAccountsByIp(ip);
                service.send(player, MessageKey.MAX_REGISTER_EXCEEDED, Integer.toString(maxRegPerIp),
                    Integer.toString(otherAccounts.size()), String.join(", ", otherAccounts));
                return false;
            }
            return true;
        }

        // Fallback to original logic
        int maxRegPerIp = service.getProperty(RestrictionSettings.MAX_REGISTRATION_PER_IP);
        if (maxRegPerIp > 0
            && ip != null
            && !InternetProtocolUtils.isLoopbackAddress(ip)
            && !service.hasPermission(player, ALLOW_MULTIPLE_ACCOUNTS)) {
            List<String> otherAccounts = database.getAllAuthsByIp(ip);
            if (otherAccounts.size() >= maxRegPerIp) {
                service.send(player, MessageKey.MAX_REGISTER_EXCEEDED, Integer.toString(maxRegPerIp),
                    Integer.toString(otherAccounts.size()), String.join(", ", otherAccounts));
                return false;
            }
        }
        return true;
    }
}
