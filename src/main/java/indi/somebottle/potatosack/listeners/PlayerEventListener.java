package indi.somebottle.potatosack.listeners;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.LocalStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;

/**
 * 玩家事件监听器
 * <p>
 * 监听玩家上线事件，用于设置全量备份标记位
 */
public class PlayerEventListener implements Listener {

    /**
     * 玩家上线事件处理
     * <p>
     * 当玩家上线时，将全量备份和增量备份标记位都设置为 true
     *
     * @param event 玩家上线事件
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            LocalStatus localStatus = LocalStatus.getInstance();
            localStatus.setFullBackupFlag(true);
            localStatus.setIncreBackupFlag(true);
        } catch (IOException e) {
            ConsoleSender.logError("[LocalStatus] Failed to set backup flags: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
