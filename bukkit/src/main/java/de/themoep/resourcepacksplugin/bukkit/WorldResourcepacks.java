package de.themoep.resourcepacksplugin.bukkit;

/*
 * ResourcepacksPlugins - bukkit
 * Copyright (C) 2018 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import de.themoep.resourcepacksplugin.bukkit.events.ResourcePackSelectEvent;
import de.themoep.resourcepacksplugin.bukkit.events.ResourcePackSendEvent;
import de.themoep.resourcepacksplugin.bukkit.internal.InternalHelper;
import de.themoep.resourcepacksplugin.bukkit.internal.InternalHelper_fallback;
import de.themoep.resourcepacksplugin.bukkit.listeners.AuthmeLoginListener;
import de.themoep.resourcepacksplugin.bukkit.listeners.DisconnectListener;
import de.themoep.resourcepacksplugin.bukkit.listeners.ProxyPackListener;
import de.themoep.resourcepacksplugin.bukkit.listeners.WorldSwitchListener;
import de.themoep.resourcepacksplugin.core.PackAssignment;
import de.themoep.resourcepacksplugin.core.PackManager;
import de.themoep.resourcepacksplugin.core.ResourcePack;
import de.themoep.resourcepacksplugin.core.ResourcepacksPlayer;
import de.themoep.resourcepacksplugin.core.ResourcepacksPlugin;
import de.themoep.resourcepacksplugin.core.UserManager;
import de.themoep.resourcepacksplugin.core.commands.PluginCommandExecutor;
import de.themoep.resourcepacksplugin.core.commands.ResetPackCommandExecutor;
import de.themoep.resourcepacksplugin.core.commands.ResourcepacksPluginCommandExecutor;
import de.themoep.resourcepacksplugin.core.commands.UsePackCommandExecutor;
import de.themoep.resourcepacksplugin.core.events.IResourcePackSelectEvent;
import de.themoep.resourcepacksplugin.core.events.IResourcePackSendEvent;
import de.themoep.utils.lang.LanguageConfig;
import de.themoep.utils.lang.bukkit.LanguageManager;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import protocolsupport.api.ProtocolSupportAPI;
import protocolsupport.api.ProtocolType;
import protocolsupport.api.ProtocolVersion;
import us.myles.ViaVersion.ViaVersionPlugin;
import us.myles.ViaVersion.api.ViaAPI;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Created by Phoenix616 on 18.03.2015.
 */
public class WorldResourcepacks extends JavaPlugin implements ResourcepacksPlugin {

    private ConfigAccessor storedPacks;

    private PackManager pm = new PackManager(this);

    private UserManager um;

    private LanguageManager lm;

    private Level loglevel = Level.INFO;

    private int serverPackFormat = Integer.MAX_VALUE;

    private InternalHelper internalHelper;

    private ViaAPI viaApi;
    private boolean protocolSupportApi = false;
    private AuthMeApi authmeApi;
    private ProxyPackListener proxyPackListener;

    public void onEnable() {
        boolean firstStart = !getDataFolder().exists();
        storedPacks = new ConfigAccessor(this, "players.yml");
        if (loadConfig()) {
            getServer().getPluginManager().registerEvents(new DisconnectListener(this), this);
            getServer().getPluginManager().registerEvents(new WorldSwitchListener(this), this);

            getServer().getMessenger().registerOutgoingPluginChannel(this, "rp:plugin");
            proxyPackListener = new ProxyPackListener(this);
            getServer().getMessenger().registerIncomingPluginChannel(this, "rp:plugin", proxyPackListener);

            registerCommand(new ResourcepacksPluginCommandExecutor(this));
            registerCommand(new UsePackCommandExecutor(this));
            registerCommand(new ResetPackCommandExecutor(this));

            String versionString = getServer().getBukkitVersion();
            int firstPoint = versionString.indexOf(".");
            int secondPoint = versionString.indexOf(".", firstPoint + 1);
            int minus = versionString.indexOf("-", firstPoint + 1);
            String versionNumberString = versionString.substring(firstPoint + 1, (secondPoint < minus && secondPoint != -1) ? secondPoint : minus);
            try {
                int serverVersion = Integer.valueOf(versionNumberString);
                if (serverVersion >= 13) {
                    serverPackFormat = 4;
                } else if (serverVersion >= 11) {
                    serverPackFormat = 3;
                } else if (serverVersion >= 9) {
                    serverPackFormat = 2;
                } else if (serverVersion >= 8) {
                    serverPackFormat = 1;
                } else {
                    serverPackFormat = 0;
                }
                getLogger().log(getLogLevel(), "Detected server packformat " + serverPackFormat + "!");
            } catch(NumberFormatException e) {
                getLogger().log(Level.WARNING, "Could not get version of the server! (" + versionString + "/" + versionNumberString + ")");
            }

            String packageName = getServer().getClass().getPackage().getName();
            String serverVersion = packageName.substring(packageName.lastIndexOf('.') + 1);

            Class<?> internalClass;
            try {
                internalClass = Class.forName(getClass().getPackage().getName() + ".internal.InternalHelper_" + serverVersion);
            } catch (Exception e) {
                internalClass = InternalHelper_fallback.class;
            }
            try {
                if(InternalHelper.class.isAssignableFrom(internalClass)) {
                    internalHelper = (InternalHelper) internalClass.getConstructor().newInstance();
                }
            } catch (Exception e) {
                internalHelper = new InternalHelper_fallback();
            }

            ViaVersionPlugin viaPlugin = (ViaVersionPlugin) getServer().getPluginManager().getPlugin("ViaVersion");
            if (viaPlugin != null && viaPlugin.isEnabled()) {
                viaApi = viaPlugin.getApi();
                getLogger().log(Level.INFO, "Detected ViaVersion " + viaApi.getVersion());
            }

            Plugin protocolSupport = getServer().getPluginManager().getPlugin("ProtocolSupport");
            if (protocolSupport != null && protocolSupport.isEnabled()) {
                protocolSupportApi = true;
                getLogger().log(Level.INFO, "Detected ProtocolSupport " + protocolSupport.getDescription().getVersion());
            }

            if (getConfig().getBoolean("autogeneratehashes", true)) {
                getPackManager().generateHashes(null);
            }

            um = new UserManager(this);

            if (!getConfig().getBoolean("disable-metrics", false)) {
                try {
                    org.mcstats.MetricsLite metrics = new org.mcstats.MetricsLite(this);
                    metrics.start();
                } catch (IOException e) {
                    // metrics failed to load
                }

                new org.bstats.MetricsLite(this);
            }

            if (firstStart || new Random().nextDouble() < 0.01) {
                startupMessage();
            }
        } else {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    protected void registerCommand(PluginCommandExecutor executor) {
        getCommand(executor.getName()).setExecutor(new ForwardingCommand(executor));
    }

    public boolean loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        storedPacks.reloadConfig();
        getLogger().log(Level.INFO, "Loading config!");
        String debugString = getConfig().getString("debug");
        if (debugString.equalsIgnoreCase("true")) {
            loglevel = Level.INFO;
        } else if (debugString.equalsIgnoreCase("false") || debugString.equalsIgnoreCase("off")) {
            loglevel = Level.FINE;
        } else {
            try {
                loglevel = Level.parse(debugString.toUpperCase());
            } catch (IllegalArgumentException e) {
                getLogger().log(Level.SEVERE, "Wrong config value for debug! To disable debugging just set it to \"false\"! (" + e.getMessage() + ")");
            }
        }
        getLogger().log(Level.INFO, "Debug level: " + getLogLevel().getName());

        lm = new LanguageManager(this, getConfig().getString("default-language"));

        getPackManager().init();
        if (getConfig().isSet("packs") && getConfig().isConfigurationSection("packs")) {
            getLogger().log(getLogLevel(), "Loading packs:");
            ConfigurationSection packs = getConfig().getConfigurationSection("packs");
            for (String s : packs.getKeys(false)) {
                ConfigurationSection packSection = packs.getConfigurationSection(s);
                String packName = s.toLowerCase();
                String packUrl = packSection.getString("url", "");
                if (packUrl.isEmpty()) {
                    getLogger().log(Level.SEVERE, "Pack " + packName + " does not have an url defined!");
                    continue;
                }
                String packHash = packSection.getString("hash", "");
                int packFormat = packSection.getInt("format", 0);
                boolean packRestricted = packSection.getBoolean("restricted", false);
                String packPerm = packSection.getString("permission", getName().toLowerCase() + ".pack." + packName);

                try {
                    getLogger().log(Level.INFO, packName + " - " + packUrl + " - " + packHash.toLowerCase());
                    ResourcePack pack = new ResourcePack(packName, packUrl, packHash, packFormat, packRestricted, packPerm);

                    getPackManager().addPack(pack);
                } catch (IllegalArgumentException e) {
                    getLogger().log(Level.SEVERE, e.getMessage());
                    continue;
                }

                if (getServer().getPluginManager().getPermission(packPerm) == null) {
                    Permission perm = new Permission(packPerm);
                    perm.setDefault(PermissionDefault.OP);
                    perm.setDescription("Permission for access to the resourcepack " + packName + " via the usepack command.");
                    try {
                        getServer().getPluginManager().addPermission(perm);
                    } catch (IllegalArgumentException ignored) {} // Permission already registered
                }
            }
        } else {
            getLogger().log(getLogLevel(), "No packs defined!");
        }

        if (getConfig().isConfigurationSection("empty")) {
            ConfigurationSection packSection = getConfig().getConfigurationSection("empty");
            String packName = PackManager.EMPTY_IDENTIFIER;
            String packUrl = packSection.getString("url", "");
            if (packUrl.isEmpty()) {
                getLogger().log(Level.SEVERE, "Empty pack does not have an url defined!");
            }
            String packHash = packSection.getString("hash", "");
            int packFormat = packSection.getInt("format", 0);

            try {
                getLogger().log(Level.INFO, packName + " - " + packUrl + " - " + packHash.toLowerCase());
                ResourcePack pack = new ResourcePack(packName, packUrl, packHash, packFormat, false, null);

                getPackManager().addPack(pack);
                getPackManager().setEmptyPack(pack);
            } catch (IllegalArgumentException e) {
                getLogger().log(Level.SEVERE, e.getMessage());
            }
        } else {
            String emptypackname = getConfig().getString("empty", null);
            if (emptypackname != null && !emptypackname.isEmpty()) {
                ResourcePack ep = getPackManager().getByName(emptypackname);
                if (ep != null) {
                    getLogger().log(Level.INFO, "Empty pack: " + ep.getName());
                    getPackManager().setEmptyPack(ep);
                } else {
                    getLogger().log(Level.WARNING, "Cannot set empty resourcepack as there is no pack with the name " + emptypackname + " defined!");
                }
            } else {
                getLogger().log(Level.WARNING, "No empty pack defined!");
            }
        }
        String name = null;
        if (getConfig().isSet("server") && getConfig().isConfigurationSection("server")) {
            name = "server";
        } else if (getConfig().isSet("global") && getConfig().isConfigurationSection("global")) {
            name = "global";
        }
        if (name != null) {
            getLogger().log(Level.INFO, "Loading " + name + " assignment...");
            ConfigurationSection globalSection = getConfig().getConfigurationSection(name);
            PackAssignment globalAssignment = getPackManager().loadAssignment(name, getValues(globalSection));
            getPackManager().setGlobalAssignment(globalAssignment);
            getLogger().log(getLogLevel(), "Loaded " + globalAssignment.toString());
        } else {
            getLogger().log(getLogLevel(), "No global server assignment defined!");
        }

        if (getConfig().isSet("worlds") && getConfig().isConfigurationSection("worlds")) {
            getLogger().log(Level.INFO, "Loading world assignments...");
            ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
            for (String world : worlds.getKeys(false)) {
                ConfigurationSection worldSection = worlds.getConfigurationSection(world);
                if (worldSection != null) {
                    getLogger().log(Level.INFO, "Loading assignment for world " + world + "...");
                    PackAssignment worldAssignment = getPackManager().loadAssignment(world, getValues(worldSection));
                    getPackManager().addAssignment(worldAssignment);
                    getLogger().log(getLogLevel(), "Loaded " + worldAssignment.toString() );
                } else {
                    getLogger().log(Level.WARNING, "Config has entry for world " + world + " but it is not a configuration section?");
                }
            }
        } else {
            getLogger().log(getLogLevel(), "No world assignments defined!");
        }

        if (getConfig().getBoolean("useauthme", true) && getServer().getPluginManager().getPlugin("AuthMe") != null) {
            authmeApi = AuthMeApi.getInstance();
            getLogger().log(Level.INFO, "Detected AuthMe " + getServer().getPluginManager().getPlugin("AuthMe").getDescription().getVersion());
            LoginEvent.getHandlerList().unregister(this);
            getServer().getPluginManager().registerEvents(new AuthmeLoginListener(this), this);
        }
        return true;
    }

    private Map<String, Object> getValues(ConfigurationSection config) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : config.getKeys(false)) {
            if (config.get(key, null) != null) {
                if (config.isConfigurationSection(key)) {
                    map.put(key, getValues(config.getConfigurationSection(key)));
                } else {
                    map.put(key, config.get(key));
                }
            }
        }
        return map;
    }

    /**
     * Reloads the configuration from the file and
     * resends the resource pack to all online players
     */
    public void reloadConfig(boolean resend) {
        loadConfig();
        getLogger().log(Level.INFO, "Reloaded config.");
        if(isEnabled() && resend) {
            getLogger().log(Level.INFO, "Resending packs for all online players!");
            um = new UserManager(this);
            for(Player p : getServer().getOnlinePlayers()) {
                resendPack(p);
            }
        }
    }

    public void saveConfigChanges() {
        for (ResourcePack pack : getPackManager().getPacks()) {
            boolean isEmptyPack = pack.equals(getPackManager().getEmptyPack());
            String path = "packs." + pack.getName();
            if (isEmptyPack && getConfig().isConfigurationSection("empty")) {
                path = "empty";
            }
            getConfig().set(path + ".url", pack.getUrl());
            getConfig().set(path + ".hash", pack.getHash());
            getConfig().set(path + ".format", !isEmptyPack ? pack.getFormat() : null);
            getConfig().set(path + ".restricted", !isEmptyPack ? pack.isRestricted() : null);
            getConfig().set(path + ".permission",!isEmptyPack ? pack.getPermission() : null);
        }
        setConfigFlat(getPackManager().getGlobalAssignment().getName(), getPackManager().getGlobalAssignment().serialize());
        for (PackAssignment assignment : getPackManager().getAssignments()) {
            setConfigFlat("worlds." + assignment.getName(), assignment.serialize());
        }
        saveConfig();
    }

    private void setConfigFlat(String rootKey, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map) {
                setConfigFlat(rootKey + "." + entry.getKey(), (Map<String, Object>) entry.getValue());
            } else {
                getConfig().set(rootKey + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void setStoredPack(UUID playerId, String packName) {
        storedPacks.getConfig().set("players." + playerId, packName);
        storedPacks.saveConfig();
    }

    @Override
    public String getStoredPack(UUID playerId) {
        return storedPacks.getConfig().getString("players." + playerId);
    }

    @Override
    public boolean isUsepackTemporary() {
        return getConfig().getBoolean("usepack-is-temporary");
    }
    
    @Override
    public int getPermanentPackRemoveTime() {
        return getConfig().getInt("permanent-pack-remove-time");
    }
    
    public void resendPack(UUID playerId) {
        Player player = getServer().getPlayer(playerId);
        if(player != null) {
            resendPack(player);
        }
    }

    /**
     * Resends the pack that corresponds to the player's world
     * @param player The player to set the pack for
     */
    public void resendPack(Player player) {
        String worldName = "";
        if(player.getWorld() != null) {
            worldName = player.getWorld().getName();
        }
        getPackManager().applyPack(player.getUniqueId(), worldName);
    }

    public void setPack(UUID playerId, ResourcePack pack) {
        getPackManager().setPack(playerId, pack);
    }

    public void sendPack(UUID playerId, ResourcePack pack) {
        Player player = getServer().getPlayer(playerId);
        if(player != null) {
            sendPack(player, pack);
        }
    }

    /**
     * Set the resourcepack of a connected player
     * @param player The ProxiedPlayer to set the pack for
     * @param pack The resourcepack to set for the player
     */
    public void sendPack(Player player, ResourcePack pack) {
        if (pack.getRawHash().length != 0) {
            internalHelper.setResourcePack(player, pack);
        } else {
            player.setResourcePack(pack.getUrl());
        }
        getLogger().log(getLogLevel(), "Send pack " + pack.getName() + " (" + pack.getUrl() + ") to " + player.getName());
    }

    public void clearPack(UUID playerId) {
        getUserManager().clearUserPack(playerId);
    }

    public void clearPack(Player player) {
        getUserManager().clearUserPack(player.getUniqueId());
    }

    public PackManager getPackManager() {
        return pm;
    }

    public UserManager getUserManager() {
        return um;
    }

    @Override
    public String getMessage(ResourcepacksPlayer sender, String key, String... replacements) {
        if (lm != null) {
            Player player = null;
            if (sender != null) {
                player = getServer().getPlayer(sender.getUniqueId());
            }
            LanguageConfig config = lm.getConfig(player);
            if (config != null) {
                return config.get(key, replacements);
            } else {
                return "Missing language config! (default language: " + lm.getDefaultLocale() + ", key: " + key + ")";
            }
        }
        return key;
    }

    @Override
    public boolean hasMessage(ResourcepacksPlayer sender, String key) {
        if (lm != null) {
            Player player = null;
            if (sender != null) {
                player = getServer().getPlayer(sender.getUniqueId());
            }
            return lm.getConfig(player).contains(key);
        }
        return false;
    }

    public String getVersion() {
        return getDescription().getVersion();
    }

    public Level getLogLevel() {
        return loglevel;
    }

    @Override
    public ResourcepacksPlayer getPlayer(UUID playerId) {
        Player player = getServer().getPlayer(playerId);
        if(player != null) {
            return new ResourcepacksPlayer(player.getName(), player.getUniqueId());
        }
        return null;
    }

    @Override
    public ResourcepacksPlayer getPlayer(String playerName) {
        Player player = getServer().getPlayer(playerName);
        if(player != null) {
            return new ResourcepacksPlayer(player.getName(), player.getUniqueId());
        }
        return null;
    }

    @Override
    public boolean sendMessage(ResourcepacksPlayer player, String key, String... replacements) {
        return sendMessage(player, Level.INFO, key, replacements);
    }

    @Override
    public boolean sendMessage(ResourcepacksPlayer packPlayer, Level level, String key, String... replacements) {
        String message = getMessage(packPlayer, key, replacements);
        if (message.isEmpty()) {
            return false;
        }
        if(packPlayer != null) {
            Player player = getServer().getPlayer(packPlayer.getUniqueId());
            if(player != null) {
                player.sendMessage(message);
                return true;
            }
        } else {
            log(level, message);
        }
        return false;
    }

    @Override
    public void log(Level level, String message) {
        getLogger().log(level, ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message)));
    }

    @Override
    public boolean checkPermission(ResourcepacksPlayer player, String perm) {
        // Console
        if(player == null)
            return true;
        return checkPermission(player.getUniqueId(), perm);

    }

    @Override
    public boolean checkPermission(UUID playerId, String perm) {
        Player player = getServer().getPlayer(playerId);
        if(player != null) {
            return player.hasPermission(perm);
        }
        return perm == null;

    }

    @Override
    public int getPlayerPackFormat(UUID playerId) {
        Player player = getServer().getPlayer(playerId);
        if (player != null) {
            int format = serverPackFormat;
            if (viaApi != null) {
                format = getPackManager().getPackFormat(viaApi.getPlayerVersion(playerId));
            }
            if (protocolSupportApi && format == serverPackFormat) { // if still same format test if player is using previous version
                ProtocolVersion version = ProtocolSupportAPI.getProtocolVersion(player);
                if (version.getProtocolType() == ProtocolType.PC) {
                    format = getPackManager().getPackFormat(version.getId());
                }
            }
            return format;
        }
        return -1;
    }

    @Override
    public IResourcePackSelectEvent callPackSelectEvent(UUID playerId, ResourcePack pack, IResourcePackSelectEvent.Status status) {
        ResourcePackSelectEvent selectEvent = new ResourcePackSelectEvent(playerId, pack, status);
        getServer().getPluginManager().callEvent(selectEvent);
        return selectEvent;
    }

    @Override
    public IResourcePackSendEvent callPackSendEvent(UUID playerId, ResourcePack pack) {
        ResourcePackSendEvent sendEvent = new ResourcePackSendEvent(playerId, pack);
        getServer().getPluginManager().callEvent(sendEvent);
        return sendEvent;
    }

    @Override
    public boolean isAuthenticated(UUID playerId) {
        if(authmeApi == null)
            return true;
        Player player = getServer().getPlayer(playerId);
        return player != null && authmeApi.isAuthenticated(player);
    }

    @Override
    public int runTask(Runnable runnable) {
        return getServer().getScheduler().runTask(this, runnable).getTaskId();
    }

    @Override
    public int runAsyncTask(Runnable runnable) {
        return getServer().getScheduler().runTaskAsynchronously(this, runnable).getTaskId();
    }

    /**
     * Get the listener that listens on the "rp:plugin" channel to register new sub channels
     * @return  The ProxyPackListener
     */
    public ProxyPackListener getProxyPackListener() {
        return proxyPackListener;
    }
}