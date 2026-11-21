package com.xiying.chain;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * XiYing 连锁挖矿 —— 移植自 LLBDS chain.js (LCH0426, GPL-3.0)
 * 支持 Paper/Purpur 1.20+，基岩玩家(Geyser/Floodgate)同样可用
 */
public class ChainPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static ChainPlugin instance;
    private FileConfiguration config;
    private final Map<UUID, PlayerCfg> playerCache = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<Long> chainMining = Collections.synchronizedSet(new HashSet<>());
    private File dataFile;

    public static ChainPlugin get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        config = getConfig();
        dataFile = new File(getDataFolder(), "players.yml");
        loadAllPlayers();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("chain")).setExecutor(this);
        Objects.requireNonNull(getCommand("chain")).setTabCompleter(this);
        getLogger().info("XiYing 连锁挖矿已启用 (移植自 LLBDS chain.js)");
        // periodic save
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveAllPlayers, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveAllPlayers();
        getLogger().info("XiYing 连锁挖矿已卸载，数据已保存");
    }

    // ---------------- config helpers ----------------

    private boolean chainEnabled() { return config.getBoolean("enabled", true); }
    private int maxBlocks() { return config.getInt("maxBlocks", 64); }
    private boolean defaultSneakOnly() { return config.getBoolean("sneakOnly", true); }

    private List<String> allowedForTool(String toolType) {
        List<String> list = config.getStringList(toolType);
        return list == null ? Collections.emptyList() : list;
    }

    private Set<String> allAllowedBlocks() {
        Set<String> all = new LinkedHashSet<>();
        for (String t : new String[]{"pickaxe","axe","shovel","hoe"}) all.addAll(allowedForTool(t));
        return all;
    }

    private boolean canToolMine(String toolType, String blockType) {
        for (String b : allowedForTool(toolType)) {
            if (normId(b).equalsIgnoreCase(normId(blockType))) return true;
        }
        return false;
    }

    /** normalize: minecraft:stone / STONE / stone -> stone */
    private String normId(String s) {
        if (s == null) return "";
        String r = s.toLowerCase(Locale.ROOT);
        if (r.startsWith("minecraft:")) r = r.substring(10);
        return r;
    }

    /** tool type from material name, e.g. DIAMOND_PICKAXE -> pickaxe */
    private String toolTypeOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        String name = item.getType().name().toLowerCase(Locale.ROOT);
        if (name.endsWith("_pickaxe")) return "pickaxe";
        if (name.endsWith("_axe")) return "axe";
        if (name.endsWith("_shovel")) return "shovel";
        if (name.endsWith("_hoe")) return "hoe";
        // bedrock/geyser item ids may differ; try legacy nms-free fallback
        if (name.contains("pickaxe")) return "pickaxe";
        if (name.contains("axe")) return "axe";
        if (name.contains("shovel") || name.contains("spade")) return "shovel";
        if (name.contains("hoe")) return "hoe";
        return null;
    }

    // ---------------- player config ----------------

    private static class PlayerCfg {
        boolean enabled = true;
        boolean sneakOnly = true;
        Set<String> disabled = new HashSet<>();
    }

    private void loadAllPlayers() {
        playerCache.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : y.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerCfg c = new PlayerCfg();
                c.enabled = y.getBoolean(key + ".enabled", true);
                c.sneakOnly = y.getBoolean(key + ".sneakOnly", defaultSneakOnly());
                c.disabled.addAll(y.getStringList(key + ".disabled"));
                playerCache.put(uuid, c);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveAllPlayers() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerCfg> e : playerCache.entrySet()) {
            String k = e.getKey().toString();
            y.set(k + ".enabled", e.getValue().enabled);
            y.set(k + ".sneakOnly", e.getValue().sneakOnly);
            y.set(k + ".disabled", new ArrayList<>(e.getValue().disabled));
        }
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning("保存玩家数据失败: " + ex.getMessage()); }
    }

    private PlayerCfg cfg(Player p) {
        return playerCache.computeIfAbsent(p.getUniqueId(), u -> {
            PlayerCfg c = new PlayerCfg();
            c.sneakOnly = defaultSneakOnly();
            return c;
        });
    }

    // ---------------- event: chain mining ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!chainEnabled()) return;
        // ignore blocks broken by our own chain (avoid recursion)
        if (chainMining.remove(key(e.getBlock()))) return;
        Player p = e.getPlayer();
        PlayerCfg pc = cfg(p);
        if (!pc.enabled) return;
        if (pc.sneakOnly && !p.isSneaking()) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < 200) return;
        cooldowns.put(p.getUniqueId(), now);

        ItemStack hand = p.getInventory().getItemInMainHand();
        String toolType = toolTypeOf(hand);
        if (toolType == null) return;
        String blockType = e.getBlock().getType().name().toLowerCase(Locale.ROOT);
        if (!canToolMine(toolType, blockType)) return;
        if (pc.disabled.contains(blockType)) return;

        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        int limit = maxBlocks();
        int unbreaking = hand.getEnchantmentLevel(Enchantment.UNBREAKING);

        if (!creative) {
            int durability = durabilityLeft(hand);
            if (durability <= 0) return;
            int effective = durability * (unbreaking + 1);
            limit = Math.min(limit, effective);
        }

        final int max = limit;
        final int ub = unbreaking;
        final boolean isCreative = creative;
        final Location origin = e.getBlock().getLocation();
        final String targetType = blockType;
        // clone the tool so Bukkit's breakNaturally(tool) applies Fortune/Silk Touch correctly
        final ItemStack tool = hand.clone();

        // defer to next tick so the originally broken block finishes processing
        getServer().getScheduler().runTask(this, () -> doChain(p, origin, targetType, max, ub, tool, isCreative));
    }

    private int durabilityLeft(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable d)) return Integer.MAX_VALUE;
        int max = item.getType().getMaxDurability();
        if (max <= 0) return Integer.MAX_VALUE;
        return max - d.getDamage();
    }

    private void doChain(Player p, Location origin, String blockType, int max, int unbreaking, ItemStack tool, boolean creative) {
        World world = origin.getWorld();
        Set<Long> visited = new HashSet<>();
        ArrayList<Block> queue = new ArrayList<>();
        Block start = world.getBlockAt(origin);
        queue.add(start);
        visited.add(key(start));
        List<Block> found = new ArrayList<>();

        int[] dx = {1,-1,0,0,0,0}, dy = {0,0,1,-1,0,0}, dz = {0,0,0,0,1,-1};
        int idx = 0;
        while (idx < queue.size() && found.size() < max) {
            Block cur = queue.get(idx++);
            for (int d = 0; d < 6; d++) {
                if (found.size() >= max) break;
                Block n = world.getBlockAt(cur.getX()+dx[d], cur.getY()+dy[d], cur.getZ()+dz[d]);
                long k = key(n);
                if (visited.contains(k)) continue;
                visited.add(k);
                if (!n.getType().name().toLowerCase(Locale.ROOT).equals(blockType)) continue;
                found.add(n);
                queue.add(n);
            }
        }
        if (found.isEmpty()) return;

        int broken = 0;
        for (Block b : found) {
            chainMining.add(key(b)); // mark: onBreak listener will skip these derived breaks
            // pass the tool so Bukkit applies Fortune (extra drops) and Silk Touch (block itself)
            b.breakNaturally(tool);
            chainMining.remove(key(b));
            broken++;
        }

        if (!creative && broken > 0) {
            applyDurability(p, broken, unbreaking);
        }

        boolean silkTag = tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;
        p.sendMessage(ChatColor.YELLOW + "[连锁] 共连锁 " + ChatColor.GREEN + broken + ChatColor.YELLOW + " 个方块" + (silkTag ? ChatColor.GRAY + "(精确采集)" : ""));
        // pull nearby drops to player (like original)
        Location ploc = p.getLocation();
        for (org.bukkit.entity.Entity en : world.getNearbyEntities(ploc, 12, 12, 12)) {
            if (en instanceof Item it) {
                if (it.getLocation().distanceSquared(ploc) < 144) it.teleport(ploc.clone().add(0,0.5,0));
            }
        }
    }

    private long key(Block b) {
        return (((long) b.getX() & 0x3FFFFFF) << 38) | (((long) b.getZ() & 0x3FFFFFF) << 12) | ((long) b.getY() & 0xFFF);
    }

    private void applyDurability(Player p, int count, int unbreaking) {
        getServer().getScheduler().runTaskLater(this, () -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) return;
            if (!(hand.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) return;
            int max = hand.getType().getMaxDurability();
            if (max <= 0) return;
            double chance = 1.0 / (unbreaking + 1);
            int dmg = 0;
            for (int i = 0; i < count; i++) if (Math.random() < chance) dmg++;
            int nd = Math.min(max - 1, d.getDamage() + dmg);
            d.setDamage(nd);
            hand.setItemMeta(d);
        }, 1L);
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("请在游戏中使用 /chain 打开连锁设置");
            return true;
        }
        if (!p.hasPermission("chain.admin")) {
            p.sendMessage(ChatColor.RED + "[连锁] 只有管理员可以调整连锁设置");
            return true;
        }
        PlayerCfg pc = cfg(p);
        if (args.length == 0) {
            showSettings(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("on")) {
            pc.enabled = true; saveAllPlayers();
            p.sendMessage(ChatColor.GREEN + "[连锁] 总开关已开启");
        } else if (sub.equals("off")) {
            pc.enabled = false; saveAllPlayers();
            p.sendMessage(ChatColor.RED + "[连锁] 总开关已关闭");
        } else if (sub.equals("sneak")) {
            pc.sneakOnly = !pc.sneakOnly; saveAllPlayers();
            p.sendMessage(ChatColor.YELLOW + "[连锁] 仅蹲下触发: " + (pc.sneakOnly ? ChatColor.GREEN+"开" : ChatColor.RED+"关"));
        } else if (sub.equals("block") && args.length >= 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!allAllowedBlocks().contains(id)) {
                p.sendMessage(ChatColor.RED + "[连锁] 这个方块不在配置允许列表里: " + id);
            } else if (pc.disabled.contains(id)) {
                pc.disabled.remove(id); saveAllPlayers();
                p.sendMessage(ChatColor.GREEN + "[连锁] " + id + " 已重新允许连锁");
            } else {
                pc.disabled.add(id); saveAllPlayers();
                p.sendMessage(ChatColor.RED + "[连锁] " + id + " 已禁用连锁");
            }
        } else if (sub.equals("reload") && p.hasPermission("chain.admin")) {
            reloadConfig();
            config = getConfig();
            loadAllPlayers();
            p.sendMessage(ChatColor.GREEN + "[连锁] 配置已重载");
        } else {
            p.sendMessage(ChatColor.YELLOW + "用法: /chain 打开设置，/chain on|off|sneak|block <ID>，/chain reload(管理员)");
        }
        return true;
    }

    private void showSettings(Player p) {
        PlayerCfg pc = cfg(p);
        p.sendMessage(ChatColor.GOLD + "===== 连锁挖矿设置 =====");
        p.sendMessage(ChatColor.YELLOW + "/chain on|off  总开关 (当前: " + (pc.enabled ? ChatColor.GREEN+"开" : ChatColor.RED+"关") + ChatColor.YELLOW + ")");
        p.sendMessage(ChatColor.YELLOW + "/chain sneak  切换「仅蹲下触发」(当前: " + (pc.sneakOnly ? ChatColor.GREEN+"开" : ChatColor.RED+"关") + ChatColor.YELLOW + ")");
        p.sendMessage(ChatColor.YELLOW + "/chain block <方块ID>  切换某个方块的连锁");
        p.sendMessage(ChatColor.GRAY + "当前禁用连锁的方块: " + (pc.disabled.isEmpty() ? "无" : String.join(", ", pc.disabled)));
        p.sendMessage(ChatColor.GRAY + "可用方块见 config.yml (pickaxe/axe/shovel/hoe)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], Arrays.asList("on","off","sneak","block","reload"), out);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("block")) {
            StringUtil.copyPartialMatches(args[1], new ArrayList<>(allAllowedBlocks()), out);
        }
        return out;
    }
}
