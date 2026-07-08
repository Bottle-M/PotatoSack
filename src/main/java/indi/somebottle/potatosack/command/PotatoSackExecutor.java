package indi.somebottle.potatosack.command;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.utils.ConsoleSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("SwitchStatementWithTooFewBranches")
public class PotatoSackExecutor implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            switch (args[0]) {
                case "reload":
                    if ((sender instanceof Player) && !sender.hasPermission("potatosack.reload") && !sender.isOp()) {
                        ConsoleSender.autoSend(sender, "You don't have permission to execute this command!");
                        return false;
                    }
                    String result = PotatoSack.getPluginInstance().reloadAll();
                    ConsoleSender.autoSend(sender, result);
                    return true;
            }
        }
        ConsoleSender.autoSend(sender, "Usage: /potatosack reload");
        return false;
    }
}
