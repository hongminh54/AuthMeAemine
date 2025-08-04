package fr.xephi.authme.command.executable.authme;

import fr.xephi.authme.TestHelper;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.IpRestrictionService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Test for {@link ResetIpCommand}.
 */
@RunWith(MockitoJUnitRunner.class)
public class ResetIpCommandTest {

    @InjectMocks
    private ResetIpCommand command;

    @Mock
    private BukkitService bukkitService;

    @Mock
    private CommonService commonService;

    @Mock
    private IpRestrictionService ipRestrictionService;

    @Mock
    private DataSource dataSource;

    @BeforeClass
    public static void setUpLogger() {
        TestHelper.setupLogger();
    }

    @Test
    public void shouldClearAllCacheWhenNoArguments() {
        // given
        CommandSender sender = mock(CommandSender.class);
        given(dataSource.clearAllLastIp()).willReturn(5);

        // when
        command.executeCommand(sender, Collections.emptyList());

        // then
        verify(ipRestrictionService).clearAllCache();
        verify(dataSource).clearAllLastIp();
        verify(commonService).send(sender, MessageKey.IP_CACHE_CLEARED);
        verify(sender).sendMessage("§aCleared IP data from 5 accounts in database.");
    }

    @Test
    public void shouldResetCacheForOnlinePlayer() {
        // given
        CommandSender sender = mock(CommandSender.class);
        Player player = mock(Player.class);
        String playerName = "TestPlayer";
        String playerIp = "192.168.1.100";

        given(bukkitService.getPlayerExact(playerName)).willReturn(player);
        TestHelper.mockIpAddressToPlayer(player, playerIp);
        given(dataSource.clearLastIpForIp(playerIp)).willReturn(3);

        // when
        command.executeCommand(sender, Collections.singletonList(playerName));

        // then
        verify(ipRestrictionService).invalidateCache(playerIp);
        verify(dataSource).clearLastIpForIp(playerIp);
        verify(commonService).send(sender, MessageKey.IP_CACHE_RESET, playerIp);
        verify(sender).sendMessage("§aIP reset completed for: " + playerIp);
        verify(sender).sendMessage("§7Cache cleared and IP data removed from 3 accounts in database.");
    }

    @Test
    public void shouldResetCacheForIpAddress() {
        // given
        CommandSender sender = mock(CommandSender.class);
        String ipAddress = "192.168.1.100";

        given(bukkitService.getPlayerExact(ipAddress)).willReturn(null);
        given(dataSource.clearLastIpForIp(ipAddress)).willReturn(2);

        // when
        command.executeCommand(sender, Collections.singletonList(ipAddress));

        // then
        verify(ipRestrictionService).invalidateCache(ipAddress);
        verify(dataSource).clearLastIpForIp(ipAddress);
        verify(commonService).send(sender, MessageKey.IP_CACHE_RESET, ipAddress);
        verify(sender).sendMessage("§aIP reset completed for: " + ipAddress);
        verify(sender).sendMessage("§7Cache cleared and IP data removed from 2 accounts in database.");
    }

    @Test
    public void shouldHandleOfflinePlayer() {
        // given
        CommandSender sender = mock(CommandSender.class);
        String playerName = "OfflinePlayer";
        
        given(bukkitService.getPlayerExact(playerName)).willReturn(null);

        // when
        command.executeCommand(sender, Collections.singletonList(playerName));

        // then
        verify(sender).sendMessage("§cPlayer OfflinePlayer is not online. Please provide an IP address instead.");
    }
}
