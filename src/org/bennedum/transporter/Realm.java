/*
 * Copyright 2012 frdfsnlght <frdfsnlght@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bennedum.transporter;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.rowset.serial.SerialClob;
import org.bennedum.transporter.api.ReservationException;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 *
 * @author frdfsnlght <frdfsnlght@gmail.com>
 */
public final class Realm {
    
    private static final Set<String> OPTIONS = new HashSet<String>();
    private static final Set<String> RESTART_OPTIONS = new HashSet<String>();
    private static final Options options;

    static {
        OPTIONS.add("name");
        OPTIONS.add("saveInterval");
        OPTIONS.add("saveSize");
        OPTIONS.add("defaultServer");
        OPTIONS.add("defaultWorld");
        OPTIONS.add("dbURL");
        OPTIONS.add("dbUsername");
        OPTIONS.add("dbPassword");
        OPTIONS.add("dbPrefix");

        RESTART_OPTIONS.add("saveInterval");
        RESTART_OPTIONS.add("dbURL");
        RESTART_OPTIONS.add("dbUsername");
        RESTART_OPTIONS.add("dbPassword");
        
        options = new Options(Realm.class, OPTIONS, "trp.realm", new OptionsListener() {
            @Override
            public void onOptionSet(Context ctx, String name, String value) {
                ctx.send("realm option '%s' set to '%s'", name, value);
                if (RESTART_OPTIONS.contains(name))
                    Config.save(ctx);
                    stop(ctx);
                    start(ctx);
            }
            @Override
            public String getOptionPermission(Context ctx, String name) {
                return name;
            }
        });
    }
    
    private static boolean started = false;
    private static Connection db;
    private static int saveAllTask = -1;
    private static int saveDirtyTask = -1;
    private static Set<String> redirectedPlayers = new HashSet<String>();
    private static final Map<String,PlayerData> dirtyPlayers = new HashMap<String,PlayerData>();
    private static Set<String> respawningPlayers = new HashSet<String>();
    
    public static boolean isStarted() {
        return started;
    }
    
    // called from main thread
    public static void start(Context ctx) {
        if (! getEnabled()) return;
        try {
            if (getName() == null)
                throw new RealmException("name is not set");
            if (getDbURL() == null)
                throw new RealmException("dbURL is not set");
            if (getDbUsername() == null)
                throw new RealmException("dbUsername is not set");
            if (getDbPassword() == null)
                throw new RealmException("dbPassword is not set");
            db = DriverManager.getConnection(getDbURL(), getDbUsername(), getRealDbPassword());
            db.setAutoCommit(true);
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            started = true;
            scheduleSaveAll();
            redirectedPlayers.clear();
            synchronized (dirtyPlayers) {
                dirtyPlayers.clear();
            }
            ctx.send("realm support started");
            
            for (Server server : Servers.getAll())
                if (server.isConnected()) server.sendRefreshData();
            
        } catch (Exception e) {
            ctx.warn("realm support cannot be started: %s", e.getMessage());
        }
    }

    // called from main thread
    public static void stop(Context ctx) {
        if (! started) return;
        started = false;
        try {
            db.close();
        } catch (SQLException se) {}
        db = null;
        if (saveAllTask != -1)
            Global.plugin.getServer().getScheduler().cancelTask(saveAllTask);
        saveAllTask = -1;
        if (saveDirtyTask != -1)
            Global.plugin.getServer().getScheduler().cancelTask(saveDirtyTask);
        saveDirtyTask = -1;
        saveDirtyPlayers();
        respawningPlayers.clear();
        ctx.send("realm support stopped");
    }

    public static void onConfigLoad(Context ctx) {}

    public static void onConfigSave() {}

    // Player events
    
    public static void onTeleport(Player player, Location toLocation) {
        if (! started) return;
        if (redirectedPlayers.contains(player.getName())) return;
        Utils.debug("realm onTeleport '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (respawningPlayers.remove(player.getName())) {
                if (data == null) return;
                sendPlayerHome(player, data.home);
            } else {
                if (data == null)
                    data = new PlayerData(player);
                else
                    data.update(player);
                data.updateLastLocation(toLocation);
                save(data);
            }
        } catch (SQLException se) {
            Utils.severe("SQL Exception while processing realm player teleport: %s", se.getMessage());
        }
    }
    
    public static void savePlayer(Player player) {
        if (! started) return;
        redirectedPlayers.remove(player.getName());
        Utils.debug("realm save '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while saving realm player: %s", se.getMessage());
        }
    }
    
    public static boolean onJoin(Player player) {
        if (! started) return false;
        redirectedPlayers.remove(player.getName());
        Utils.debug("realm onJoin '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            String toServer;
            if ((data == null) || (data.lastServer == null)) {
                // player is new to the realm
                toServer = getDefaultServer();
            } else {
                toServer = data.lastServer;
            }
            if ((toServer != null) && (! toServer.equals(Global.plugin.getServer().getServerName()))) {
                if (sendPlayerToServer(player, toServer))
                    return true;
            }
            
            if (data != null)
                data.apply(player);
            
            String toWorld;
            if ((data == null) || (data.lastWorld == null)) {
                // player is new to this server
                toWorld = getDefaultWorld();
            } else {
                toWorld = data.lastWorld;
            }
            if ((toWorld != null) && (! toWorld.equals(player.getWorld().getName())))
                sendPlayerToWorld(player, toWorld, ((data == null) || (data.lastWorld == null)) ? null : data.lastLocation);
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.lastJoin = Calendar.getInstance();
            save(data);
            return false;
        } catch (SQLException se) {
            Utils.severe("SQL Exception while processing realm player join: %s", se.getMessage());
            return false;
        }
    }
    
    public static void onQuit(Player player) {
        if (! started) return;
        if (redirectedPlayers.contains(player.getName())) return;
        Utils.debug("realm onQuit '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.lastQuit = Calendar.getInstance();
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while processing realm player quit: %s", se.getMessage());
        }
    }
    
    public static void onKick(Player player) {
        if (! started) return;
        if (redirectedPlayers.contains(player.getName())) return;
        Utils.debug("realm onKick '%s'", player.getName());
        
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.lastKick = Calendar.getInstance();
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while processing realm player kick: %s", se.getMessage());
        }
    }
    
    public static void onDeath(Player player, String message) {
        if (! started) return;
        Utils.debug("realm onDeath '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.lastDeath = Calendar.getInstance();
            data.deaths++;
            data.lastDeathMessage = message;
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while processing realm player death: %s", se.getMessage());
        }
    }
    
    public static void onRespawn(Player player) {
        if (! started) return;
        Utils.debug("realm onRespawn '%s'", player.getName());
        respawningPlayers.add(player.getName());
    }
    
    public static void onSetHome(Player player, Location loc) {
        if (! started) return;
        Utils.debug("realm onSetHome '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.home = Global.plugin.getServer().getServerName() + "|" + loc.getWorld().getName() + "|" + loc.getX() + "|" + loc.getY() + "|" + loc.getZ();
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while setting realm player home: %s", se.getMessage());
        }
    }

    public static void onUnsetHome(Player player) {
        if (! started) return;
        Utils.debug("realm onUnsetHome '%s'", player.getName());
        try {
            PlayerData data = PlayerData.load(player.getName());
            if (data == null)
                data = new PlayerData(player);
            else
                data.update(player);
            data.home = null;
            save(data);
        } catch (SQLException se) {
            Utils.severe("SQL Exception while unsetting realm player home: %s", se.getMessage());
        }
    }
    
    // End Player events
    
    private static void save(PlayerData data) {
        synchronized (dirtyPlayers) {
            dirtyPlayers.put(data.name, data);
            if (saveDirtyTask == -1) {
                saveDirtyTask = Utils.worker(new Runnable() {
                    @Override
                    public void run() {
                        saveDirtyPlayers();
                    }
                });
            }
        }
    }

    // called from any thread
    private static void saveDirtyPlayers() {
        synchronized (dirtyPlayers) {
            for (Iterator<PlayerData> i = dirtyPlayers.values().iterator(); i.hasNext(); ) {
                PlayerData data = i.next();
                try {
                    data.save();
                } catch (SQLException se) {
                    Utils.severe("SQL Exception while saving realm player '%s': %s", data.name, se.getMessage());
                }
            }
            dirtyPlayers.clear();
            saveDirtyTask = -1;
        }
    }
        
    private static boolean sendPlayerToServer(Player player, String toServer) {
        Server server = Servers.get(toServer);
        if (server == null) {
            Utils.warning("Unknown realm server '%s' for player '%s'", toServer, player.getName());
            return false;
        }
        if (! server.isConnected()) {
            Utils.warning("Offline realm server '%s' for player '%s'", toServer, player.getName());
            return false;
        }
        String kickMessage = server.getKickMessage(player.getAddress());
        if (kickMessage == null) return false;
        Utils.debug("kicking player '%s' @%s: %s", player.getName(), player.getAddress().getAddress().getHostAddress(), kickMessage);
        redirectedPlayers.add(player.getName());
        player.kickPlayer(kickMessage);
        return true;
    }
    
    private static void sendPlayerHome(Player player, String home) {
        if (home == null) return;
        Utils.debug("sending realm player '%s' home to %s", player.getName(), home);
        String[] parts = home.split("\\|");
        if (parts.length != 5) {
            Utils.warning("Invalid realm home for player '%s': %s", player.getName(), home);
            return;
        }
        String homeServer = parts[0];
        String homeWorld = parts[1];
        double x, y, z;
        try {
            x = Double.parseDouble(parts[2]);
            y = Double.parseDouble(parts[3]);
            z = Double.parseDouble(parts[4]);
        } catch (NumberFormatException nfe) {
            Utils.warning("Invalid realm home coordinates for player '%s': %s", player.getName(), home);
            return;
        }
        
        if (! homeServer.equals(Global.plugin.getServer().getServerName())) {
            Server server = Servers.get(homeServer);
            if (server == null) {
                Utils.warning("Unknown realm home server '%s' for player '%s'", homeServer, player.getName());
                return;
            }
            if (! server.isConnected()) {
                Utils.warning("Offline realm home server '%s' for player '%s'", homeServer, player.getName());
                return;
            }

            try {
                ReservationImpl res = new ReservationImpl(player, server, homeWorld, x, y, z);
                res.depart();
            } catch (ReservationException re) {
                Utils.warning("Reservation exception while sending player '%s' to realm home '%s': %s", player.getName(), home, re.getMessage());
            }
            //return;
        }
        
        // already on the right server
        
        /*
        World world = Global.plugin.getServer().getWorld(homeWorld);
        if (world == null) {
            Utils.warning("Unknown realm home world '%s' for player '%s'", homeWorld, player.getName());
            return;
        }
        Location toLocation = new Location(world, x, y, z);
        player.teleport(toLocation);
        */
    }
    
    private static void sendPlayerToWorld(Player player, String toWorld, String toCoords) {
        World world = Global.plugin.getServer().getWorld(toWorld);
        Location toLocation;
        if (world == null) {
            Utils.warning("Unknown realm world '%s' for player '%s'", toWorld, player.getName());
            return;
        }
        if (toCoords == null)
            toLocation = world.getSpawnLocation();
        else {
            String[] coords = toCoords.split(",");
            if (coords.length != 3) {
                Utils.warning("Invalid location coordinates '%s' for player '%s'", toCoords, player.getName());
                return;
            }
            try {
                double x = Double.parseDouble(coords[0]);
                double y = Double.parseDouble(coords[1]);
                double z = Double.parseDouble(coords[2]);
                toLocation = new Location(world, x, y, z);
            } catch (NumberFormatException nfe) {
                Utils.warning("Invalid location coordinates '%s' for player '%s'", toCoords, player.getName());
                return;
            }
        }
        Utils.debug("teleporting player '%s' to %s", player.getName(), toLocation);
        player.teleport(toLocation);
    }
    
    private static List<String> savedPlayers = new ArrayList<String>();
    
    private static void scheduleSaveAll() {
        if (saveAllTask != -1)
            Global.plugin.getServer().getScheduler().cancelTask(saveAllTask);
        saveAllTask = Utils.fireDelayed(new Runnable() {
            @Override
            public void run() {
                Utils.debug("realm save all players started");
                for (OfflinePlayer player : Global.plugin.getServer().getOfflinePlayers()) {
                    redirectedPlayers.remove(player.getName());
                    savedPlayers.remove(player.getName());
                }
                for (Player player : Global.plugin.getServer().getOnlinePlayers())
                    if (! savedPlayers.contains(player.getName()))
                        savedPlayers.add(player.getName());
                if (! savedPlayers.isEmpty()) {
                    for (int playerNum = 0; playerNum < Math.min(getSaveSize(), savedPlayers.size()); playerNum++) {
                        String playerName = savedPlayers.remove(0);
                        Utils.debug("saving realm player '%s'", playerName);
                        try {
                            Player player = Global.plugin.getServer().getPlayer(playerName);
                            if (player != null) {
                                PlayerData data = PlayerData.load(playerName);
                                if (data == null)
                                    data = new PlayerData(player);
                                else
                                    data.update(player);
                                save(data);
                            }
                        } catch (SQLException se) {
                            Utils.severe("SQL Exception while loading realm player '%s': %s", playerName, se.getMessage());
                        }
                        savedPlayers.add(playerName);
                    }
                }
                saveAllTask = -1;
                scheduleSaveAll();
                Utils.debug("realm save all players completed");
            }
        }, getSaveInterval());
    }
    
    /*
    private static void saveAll() {
        
        
        final Set<PlayerData> players = new HashSet<PlayerData>();
        for (Player player : Global.plugin.getServer().getOnlinePlayers())
            players.add(new PlayerData(player));
        
        Utils.worker(new Runnable() {
            @Override
            public void run() {
                try {
                    for (PlayerData player : players) {
                        PlayerData data = PlayerData.load(player.name);
                        if (data == null)
                            data = player;
                        else
                            data.update(player);
                        save(data);
                    }
                } catch (SQLException se) {
                    Utils.severe("SQL Exception while loading realm players: %s", se.getMessage());
                }
            }
        });
    }
*/
    
    public static boolean getEnabled() {
        return Config.getBooleanDirect("realm.enabled", false);
    }

    public static void setEnabled(Context ctx, boolean b) {
        Config.setPropertyDirect("realm.enabled", b);
        stop(ctx);
        if (b) start(ctx);
    }
    
    /* Begin options */

    public static String getName() {
        return Config.getStringDirect("realm.name", null);
    }

    public static void setName(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.name", s);
    }
    
    public static int getSaveInterval() {
        return Config.getIntDirect("realm.saveInterval", 20000);
    }

    public static void setSaveInterval(int i) {
        if (i < 1000)
            throw new IllegalArgumentException("saveInterval must be at least 1000");
        Config.setPropertyDirect("realm.saveInterval", i);
    }

    public static int getSaveSize() {
        return Config.getIntDirect("realm.saveSize", 3);
    }

    public static void setSaveSize(int i) {
        if (i < 1)
            throw new IllegalArgumentException("saveSize must be at least 1");
        Config.setPropertyDirect("realm.saveSize", i);
    }

    public static String getDefaultServer() {
        return Config.getStringDirect("realm.defaultServer", null);
    }

    public static void setDefaultServer(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.defaultServer", s);
    }

    public static String getDefaultWorld() {
        return Config.getStringDirect("realm.defaultWorld", null);
    }

    public static void setDefaultWorld(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.defaultWorld", s);
    }

    public static String getDbURL() {
        return Config.getStringDirect("realm.dbURL", null);
    }

    public static void setDbURL(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.dbURL", s);
    }

    public static String getDbUsername() {
        return Config.getStringDirect("realm.dbUsername", null);
    }

    public static void setDbUsername(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.dbUsername", s);
    }

    public static String getDbPassword() {
        if (getRealDbPassword() == null) return null;
        return "*******";
    }

    public static String getRealDbPassword() {
        return Config.getStringDirect("realm.dbPassword", null);
    }

    public static void setDbPassword(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        Config.setPropertyDirect("realm.dbPassword", s);
    }

    public static String getDbPrefix() {
        return Config.getStringDirect("realm.dbPrefix", null);
    }

    public static void setDbPrefix(String s) {
        if ((s != null) && (s.equals("-") || s.equals("*"))) s = null;
        if (s != null) {
            if (! s.matches("^\\w+$"))
                throw new IllegalArgumentException("illegal character");
        }
        Config.setPropertyDirect("realm.dbPrefix", s);
    }


    public static void getOptions(Context ctx, String name) throws OptionsException, PermissionsException {
        options.getOptions(ctx, name);
    }

    public static String getOption(Context ctx, String name) throws OptionsException, PermissionsException {
        return options.getOption(ctx, name);
    }

    public static void setOption(Context ctx, String name, String value) throws OptionsException, PermissionsException {
        options.setOption(ctx, name, value);
    }

    /* End options */

    private static String tableName(String baseName) {
        String pre = getDbPrefix();
        if (pre == null) return baseName;
        return pre + baseName;
    }
    
    private static final class PlayerData {
        boolean fromDb = false;
        String name;
        String displayName;
        String address;
        List<TypeMap> inventory;
        List<TypeMap> armor;
        int heldItemSlot;
        int health;
        int remainingAir;
        int fireTicks;
        int foodLevel;
        float exhaustion;
        float saturation;
        String gameMode;
        int level;
        float xp;
        List<TypeMap> potionEffects;

        String lastServer;
        String lastWorld;
        String lastLocation;
        String home;
        
        Calendar lastJoin;
        Calendar lastQuit;
        Calendar lastKick;
        Calendar lastDeath;
        int deaths = 0;
        String lastDeathMessage;
        Calendar lastUpdated;
        
        static PlayerData load(String playerName) throws SQLException {
            synchronized (dirtyPlayers) {
                PlayerData data = dirtyPlayers.remove(playerName);
                if (data != null) return data;
            }
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = db.prepareStatement("select * from " + tableName("players") + " where name=?");
                stmt.setString(1, playerName);
                rs = stmt.executeQuery();
                if (! rs.next()) return null;
                return new PlayerData(rs);
            } finally {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
            }
        }

        private PlayerData(ResultSet rs) throws SQLException {
            fromDb = true;
            name = rs.getString("name");
            displayName = rs.getString("displayName");
            address = rs.getString("address");
            
            Clob invClob = rs.getClob("inventory");
            if (invClob == null)
                inventory = null;
            else
                inventory = (List<TypeMap>)JSON.decode(invClob.getSubString(1L, (int)invClob.length()));
            
            Clob armorClob = rs.getClob("armor");
            if (armorClob == null)
                armor = null;
            else
                armor = (List<TypeMap>)JSON.decode(armorClob.getSubString(1L, (int)armorClob.length()));
                    
            heldItemSlot = rs.getInt("heldItemSlot");
            health = rs.getInt("health");
            remainingAir = rs.getInt("remainingAir");
            fireTicks = rs.getInt("fireTicks");
            foodLevel = rs.getInt("foodLevel");
            exhaustion = rs.getFloat("exhaustion");
            saturation = rs.getFloat("saturation");
            gameMode = rs.getString("gameMode");
            level = rs.getInt("level");
            xp = rs.getFloat("xp");
            
            Clob peClob = rs.getClob("potionEffects");
            if (peClob == null)
                potionEffects = null;
            else
                potionEffects = (List<TypeMap>)JSON.decode(peClob.getSubString(1L, (int)peClob.length()));

            lastServer = rs.getString("lastServer");
            lastWorld = rs.getString("lastWorld");
            lastLocation = rs.getString("lastLocation");
            home = rs.getString("home");
            
            lastJoin = getDateTime(rs, "lastJoin");
            lastQuit = getDateTime(rs, "lastQuit");
            lastKick = getDateTime(rs, "lastKick");
            lastDeath = getDateTime(rs, "lastDeath");
            deaths = rs.getInt("deaths");
            lastDeathMessage = rs.getString("lastDeathMessage");
            lastUpdated = getDateTime(rs, "lastUpdated");
            
            Utils.debug("loaded realm player '%s'", name);
        }

        PlayerData(Player player) {
            name = player.getName();
            update(player);
        }
        
        void update(Player player) {
            displayName = player.getDisplayName();
            address = player.getAddress().getAddress().getHostAddress();
            PlayerInventory inv = player.getInventory();
            inventory = Inventory.encodeItemStackArray(inv.getContents());
            armor = Inventory.encodeItemStackArray(inv.getArmorContents());
            heldItemSlot = inv.getHeldItemSlot();
            health = player.getHealth();
            remainingAir = player.getRemainingAir();
            fireTicks = player.getFireTicks();
            foodLevel = player.getFoodLevel();
            exhaustion = player.getExhaustion();
            saturation = player.getSaturation();
            gameMode = player.getGameMode().toString();
            level = player.getLevel();
            xp = player.getExp();
            potionEffects = PotionEffects.encodePotionEffects(player.getActivePotionEffects());

            lastServer = Global.plugin.getServer().getServerName();
            if (lastServer == null) lastServer = "unknown";
            Location loc = player.getLocation();
            lastWorld = loc.getWorld().getName();
            updateLastLocation(loc);
        }
        
        void update(PlayerData data) {
            displayName = data.displayName;
            address = data.address;
            inventory = data.inventory;
            armor = data.armor;
            heldItemSlot = data.heldItemSlot;
            health = data.health;
            remainingAir = data.remainingAir;
            fireTicks = data.fireTicks;
            foodLevel = data.foodLevel;
            exhaustion = data.exhaustion; 
            saturation = data.saturation; 
            gameMode = data.gameMode;
            level = data.level;
            xp = data.xp;
            potionEffects = data.potionEffects;

            lastServer = data.lastServer;
            lastWorld = data.lastWorld;
            lastLocation = data.lastLocation;
        }

        void updateLastLocation(Location loc) {
            lastLocation = loc.getX() + "," + loc.getY() + "," + loc.getZ();
        }

        private Calendar getDateTime(ResultSet rs, String column) throws SQLException {
            Timestamp ts = rs.getTimestamp(column);
            if (ts == null) return null;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ts.getTime());
            return c;
        }

        private void setDateTime(PreparedStatement stmt, int col, Calendar c) throws SQLException {
            if (c == null)
                stmt.setTimestamp(col, null);
            else {
                Timestamp ts = new Timestamp(c.getTimeInMillis());
                stmt.setTimestamp(col, ts);
            }
        }
        
        void save() throws SQLException {
            PreparedStatement stmt = null;
            try {
                if (fromDb)
                    stmt = db.prepareStatement("update " + tableName("players") + " set " +
                                "displayName=?, " +
                                "address=?, " +
                                "inventory=?, " +
                                "armor=?, " +
                                "heldItemSlot=?, " +
                                "health=?, " +
                                "remainingAir=?, " +
                                "fireTicks=?, " +
                                "foodLevel=?, " +
                                "exhaustion=?, " +
                                "saturation=?, " +
                                "gameMode=?, " +
                                "level=?, " +
                                "xp=?, " +
                                "potionEffects=?, " +
                                "lastServer=?, " +
                                "lastWorld=?, " +
                                "lastLocation=?, " +
                                "home=?, " +
                                "lastJoin=?, " +
                                "lastQuit=?, " +
                                "lastKick=?, " +
                                "lastDeath=?, " +
                                "deaths=?, " +
                                "lastDeathMessage=?, " +
                                "lastUpdated=? " +
                            "where name=?");
                else
                    stmt = db.prepareStatement("insert into " + tableName("players") + " (" +
                                "displayName," +
                                "address," +
                                "inventory," +
                                "armor," +
                                "heldItemSlot," +
                                "health," +
                                "remainingAir," +
                                "fireTicks," +
                                "foodLevel," +
                                "exhaustion," +
                                "saturation," +
                                "gameMode," +
                                "level," +
                                "xp," +
                                "potionEffects," +
                                "lastServer," +
                                "lastWorld," +
                                "lastLocation," +
                                "home," +
                                "lastJoin," +
                                "lastQuit," +
                                "lastKick," +
                                "lastDeath," +
                                "deaths," +
                                "lastDeathMessage," +
                                "lastUpdated," +
                                "name" +
                            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                int col = 1;
                stmt.setString(col++, displayName);
                stmt.setString(col++, address);
                
                Clob invClob = new SerialClob(JSON.encode(inventory).toCharArray());
                stmt.setClob(col++, invClob);
                
                Clob armorClob = new SerialClob(JSON.encode(armor).toCharArray());
                stmt.setClob(col++, armorClob);
                
                stmt.setInt(col++, heldItemSlot);
                stmt.setInt(col++, health);
                stmt.setInt(col++, remainingAir);
                stmt.setInt(col++, fireTicks);
                stmt.setInt(col++, foodLevel);
                stmt.setFloat(col++, exhaustion);
                stmt.setFloat(col++, saturation);
                stmt.setString(col++, gameMode);
                stmt.setInt(col++, level);
                stmt.setFloat(col++, xp);
                
                Clob peClob = new SerialClob(JSON.encode(potionEffects).toCharArray());
                stmt.setClob(col++, peClob);
                
                stmt.setString(col++, lastServer);
                stmt.setString(col++, lastWorld);
                stmt.setString(col++, lastLocation);
                stmt.setString(col++, home);
                setDateTime(stmt, col++, lastJoin);
                setDateTime(stmt, col++, lastQuit);
                setDateTime(stmt, col++, lastKick);
                setDateTime(stmt, col++, lastDeath);
                stmt.setInt(col++, deaths);
                stmt.setString(col++, lastDeathMessage);
                lastUpdated = Calendar.getInstance();
                setDateTime(stmt, col++, lastUpdated);

                stmt.setString(col++, name);
                
                stmt.execute();
                Utils.debug("saved realm player '%s'", name);
            } finally {
                if (stmt != null) stmt.close();
            }
        }
        
        
        // call from main thread
        boolean apply(Player player) {
            player.setHealth(health);
            player.setRemainingAir(remainingAir);
            player.setFoodLevel(foodLevel);
            player.setExhaustion(exhaustion);
            player.setSaturation(saturation);
            if (gameMode != null)
                player.setGameMode(Utils.valueOf(GameMode.class, gameMode));
            player.setLevel(level);
            player.setExp(xp);
            player.setFireTicks(fireTicks);
            PlayerInventory inv = player.getInventory();
            ItemStack[] invArray = Inventory.decodeItemStackArray(inventory);

            if (invArray != null)
                for (int slot = 0; slot < invArray.length; slot++) {
                    ItemStack stack = invArray[slot];
                    if (stack == null)
                        inv.setItem(slot, new ItemStack(Material.AIR.getId()));
                    else
                        inv.setItem(slot, stack);
                }
            
            ItemStack[] armorArray = Inventory.decodeItemStackArray(armor);
            if (armorArray != null)
                inv.setArmorContents(armorArray);
            PotionEffect[] potionArray = PotionEffects.decodePotionEffects(potionEffects);
            if (potionArray != null) {
                for (PotionEffectType pet : PotionEffectType.values()) {
                    if (pet == null) continue;
                    if (player.hasPotionEffect(pet))
                        player.removePotionEffect(pet);
                }
                for (PotionEffect effect : potionArray) {
                    if (effect == null) continue;
                    player.addPotionEffect(effect);
                }
            }
            player.saveData();
            Utils.debug("restored realm player '%s'", name);
            return true;
        }
        
    }
    
}
