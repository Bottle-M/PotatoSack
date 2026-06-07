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
     * 当玩家上线时，将全量备份标记位设置为 true
     *
     * @param event 玩家上线事件
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            LocalStatus.getInstance().setFullBackupFlag(true);
        } catch (IOException e) {
            ConsoleSender.logError("[LocalStatus] Failed to set full backup flag: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
