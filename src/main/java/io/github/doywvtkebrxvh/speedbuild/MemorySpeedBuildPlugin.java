package io.github.doywvtkebrxvh.speedbuild;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A deliberately self-contained Paper plugin for a small memory speed-build minigame.
 * It uses only Bukkit/Paper's public API; no WorldEdit, NMS, database, or external library is required.
 */
public final class MemorySpeedBuildPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "speedbuild.admin";
    private static final String PREFIX = ChatColor.DARK_AQUA + "[速建] " + ChatColor.RESET;
    private static final int MAX_PLAYERS = 8;
    private static final int BUILD_WIDTH = 7;
    private static final int BUILD_RADIUS = 3;
    private static final int COUNTDOWN_SECONDS = 30;
    private static final int MEMORY_SECONDS = 15;
    private static final Random RANDOM = new Random();

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, BuildData> builds = new LinkedHashMap<>();
    private final Set<String> templates = new LinkedHashSet<>();
    private final Map<UUID, String> spectatorRejoinArena = new HashMap<>();

    private File stateFile;
    private Point survivalPoint;
    private BukkitTask tickTask;
    private BukkitTask secondTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");
        loadState();
        loadConfiguredWorlds();

        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommand("sb");
        registerCommand("sbforce");

        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::gameTick, 1L, 1L);
        secondTask = Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        getLogger().info("MemorySpeedBuild 已启用。已加载 " + arenas.size() + " 个竞技场与 " + builds.size() + " 个建筑。");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (secondTask != null) secondTask.cancel();

        for (Arena arena : arenas.values()) {
            removeJudge(arena);
            for (PlayerState state : arena.players.values()) {
                Player player = Bukkit.getPlayer(state.uuid);
                if (player != null && player.isOnline()) {
                    returnToSurvival(player, false);
                }
            }
            if (arena.world() != null) {
                arena.world().setGameRule(GameRule.KEEP_INVENTORY, true);
                arena.world().save();
            }
        }
        saveState();
    }

    private void registerCommand(String name) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "plugin.yml 缺少命令 " + name);
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sbforce")) {
            return handleForce(sender, args);
        }
        return handleSb(sender, args);
    }

    private boolean handleSb(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, ChatColor.AQUA + "/sb join <地图>" + ChatColor.GRAY + " 加入竞技场；" + ChatColor.AQUA + "/sb quit" + ChatColor.GRAY + " 离开。管理员输入 /sb help 查看完整命令。");
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("help")) {
            showHelp(sender);
            return true;
        }
        if (first.equals("join")) {
            if (!(sender instanceof Player player)) {
                send(sender, ChatColor.RED + "该命令只能由玩家执行。");
                return true;
            }
            if (args.length != 2) return usage(sender, "/sb join <地图名称>");
            joinArena(player, args[1]);
            return true;
        }
        if (first.equals("quit")) {
            if (!(sender instanceof Player player)) {
                send(sender, ChatColor.RED + "该命令只能由玩家执行。");
                return true;
            }
            quitArena(player, true);
            return true;
        }

        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, ChatColor.RED + "你没有管理员权限。需要 " + ADMIN_PERMISSION + "。");
            return true;
        }
        if (!(sender instanceof Player player) && requiresPlayer(first)) {
            send(sender, ChatColor.RED + "此命令只能由玩家执行。");
            return true;
        }

        try {
            switch (first) {
                case "set" -> handleSet(sender, args);
                case "create" -> handleCreate((Player) sender, args);
                case "save" -> handleSave((Player) sender, args);
                case "leave" -> handleLeave((Player) sender, args);
                case "addbuild" -> handleAddBuild((Player) sender, args);
                case "delbuild" -> handleDelBuild(sender, args);
                case "listbuild" -> handleListBuild(sender);
                case "add" -> handleAdd((Player) sender, args);
                case "addisland" -> handleAddIsland((Player) sender, args);
                case "del" -> handleDel((Player) sender, args);
                case "register" -> handleRegister(sender, args, true);
                case "unregister" -> handleRegister(sender, args, false);
                case "edit" -> handleEdit((Player) sender, args);
                default -> showHelp(sender);
            }
        } catch (NumberFormatException ex) {
            send(sender, ChatColor.RED + "坐标、编号或难度必须是整数。");
        } catch (IllegalArgumentException ex) {
            send(sender, ChatColor.RED + ex.getMessage());
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "处理命令时发生异常", ex);
            send(sender, ChatColor.RED + "执行失败。详情已写入服务器控制台。");
        }
        return true;
    }

    private boolean requiresPlayer(String root) {
        return !root.equals("listbuild") && !root.equals("delbuild") && !root.equals("register") && !root.equals("unregister") && !root.equals("help");
    }

    private boolean handleForce(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, ChatColor.RED + "你没有管理员权限。需要 " + ADMIN_PERMISSION + "。");
            return true;
        }
        if (args.length != 1) return usage(sender, "/sbforce <start|stop>");
        Arena arena = contextArena(sender);
        if (arena == null) {
            send(sender, ChatColor.RED + "请站在目标竞技场世界内执行，或确保只有一个等待/进行中的竞技场。");
            return true;
        }
        if (args[0].equalsIgnoreCase("start")) {
            if (arena.phase == Phase.RECOVERING || arena.phase.isGame()) {
                send(sender, ChatColor.RED + "该竞技场当前不能强制开始。");
                return true;
            }
            if (arena.players.isEmpty()) {
                send(sender, ChatColor.RED + "竞技场没有已加入的玩家。");
                return true;
            }
            startCountdown(arena, true);
            send(sender, ChatColor.GREEN + "已强制开始 30 秒倒计时。");
        } else if (args[0].equalsIgnoreCase("stop")) {
            if (arena.phase == Phase.IDLE && arena.players.isEmpty()) {
                send(sender, ChatColor.RED + "该竞技场当前没有活动对局。");
                return true;
            }
            forceStop(arena, ChatColor.RED + "管理员强制终止了比赛。");
            send(sender, ChatColor.GREEN + "已终止比赛并遣返所有玩家。");
        } else {
            usage(sender, "/sbforce <start|stop>");
        }
        return true;
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;
        if (args.length == 2 && args[1].equalsIgnoreCase("survival")) {
            survivalPoint = Point.from(player.getLocation());
            saveState();
            send(player, ChatColor.GREEN + "已设置生存主世界返回点：" + formatPoint(survivalPoint) + "。");
        } else {
            usage(sender, "/sb set survival");
        }
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length != 3) returnUsage(player, "/sb create <template|map> <地图名称>");
        String kind = args[1].toLowerCase(Locale.ROOT);
        String key = validKey(args[2], "地图名称");

        if (kind.equals("template")) {
            if (templates.contains(key)) throw new IllegalArgumentException("模板地图已存在。");
            World world = createVoidWorld(templateWorldName(key));
            templates.add(key);
            createPlatform(world, 0, 64, 0);
            world.setSpawnLocation(new Location(world, 0.5, 65.0, 0.5));
            player.teleport(new Location(world, 0.5, 65.0, 0.5));
            saveState();
            send(player, ChatColor.GREEN + "模板地图 " + key + " 已创建并传送。请在 7×7 范围内搭建标准建筑。");
            return;
        }
        if (kind.equals("map")) {
            if (arenas.containsKey(key)) throw new IllegalArgumentException("竞技场地图已存在。");
            World world = createVoidWorld(arenaWorldName(key));
            createPlatform(world, 0, 64, 0);
            world.setSpawnLocation(new Location(world, 0.5, 65.0, 0.5));
            Arena arena = new Arena(key, arenaWorldName(key));
            arenas.put(key, arena);
            player.teleport(new Location(world, 0.5, 65.0, 0.5));
            saveState();
            send(player, ChatColor.GREEN + "竞技场 " + key + " 已创建并传送。请先配置出生点、岛屿、中心岛和死亡复活点。");
            return;
        }
        throw new IllegalArgumentException("类型只能是 template 或 map。");
    }

    private void handleSave(Player player, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("template")) {
            String key = validKey(args[2], "地图名称");
            if (!templates.contains(key)) throw new IllegalArgumentException("模板地图不存在。");
            World world = Bukkit.getWorld(templateWorldName(key));
            if (world == null) throw new IllegalArgumentException("模板世界当前未加载。");
            world.save();
            saveState();
            send(player, ChatColor.GREEN + "模板地图 " + key + " 已保存。");
            return;
        }
        if (args.length == 2) {
            String key = validKey(args[1], "地图名称");
            Arena arena = requireArena(key);
            requireIdleForEditing(arena);
            if (arena.world() == null) throw new IllegalArgumentException("竞技场世界当前未加载。");
            arena.world().save();
            saveState();
            send(player, ChatColor.GREEN + "竞技场 " + key + " 已保存。");
            return;
        }
        returnUsage(player, "/sb save template <地图名称> 或 /sb save <地图名称>");
    }

    private void handleLeave(Player player, String[] args) {
        if (args.length != 2 || !args[1].equalsIgnoreCase("template")) {
            returnUsage(player, "/sb leave template");
            return;
        }
        if (!isTemplateWorld(player.getWorld())) {
            throw new IllegalArgumentException("你当前不在模板世界中。");
        }
        returnToSurvival(player, false);
        send(player, ChatColor.GREEN + "已返回生存主世界。");
    }

    private void handleAddBuild(Player player, String[] args) {
        if (args.length != 9) {
            returnUsage(player, "/sb addbuild <x1> <y1> <z1> <x2> <y2> <z2> <name> <1|2|3>");
            return;
        }
        if (!isIsolatedBuildWorld(player.getWorld())) {
            throw new IllegalArgumentException("请在速建模板世界或竞技场编辑世界中执行该命令。");
        }
        Arena current = arenaByWorld(player.getWorld());
        if (current != null) requireIdleForEditing(current);

        int x1 = Integer.parseInt(args[1]);
        int y1 = Integer.parseInt(args[2]);
        int z1 = Integer.parseInt(args[3]);
        int x2 = Integer.parseInt(args[4]);
        int y2 = Integer.parseInt(args[5]);
        int z2 = Integer.parseInt(args[6]);
        String key = validKey(args[7], "建筑名称");
        int difficulty = Integer.parseInt(args[8]);
        if (difficulty < 1 || difficulty > 3) throw new IllegalArgumentException("难度只能是 1、2 或 3。");

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        if (maxX - minX + 1 != BUILD_WIDTH || maxZ - minZ + 1 != BUILD_WIDTH) {
            throw new IllegalArgumentException("建筑框选的 X 和 Z 必须都恰好为 7 格；请包含空白位置以保持标准基准。");
        }
        if (minY < player.getWorld().getMinHeight() || maxY >= player.getWorld().getMaxHeight()) {
            throw new IllegalArgumentException("Y 坐标超出当前世界范围。");
        }

        BuildData build = captureBuild(player.getWorld(), key, difficulty, minX, minY, minZ, maxY);
        builds.put(key, build);
        saveState();
        send(player, ChatColor.GREEN + "已保存建筑 " + ChatColor.AQUA + build.name + ChatColor.GREEN + "，难度 " + difficulty + "，高度 " + build.height + "，方块数 " + build.blocks.size() + "。");
    }

    private BuildData captureBuild(World world, String key, int difficulty, int minX, int minY, int minZ, int maxY) {
        List<BlockSnapshot> snapshots = new ArrayList<>();
        Set<Material> unsupported = new LinkedHashSet<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x < minX + BUILD_WIDTH; x++) {
                for (int z = minZ; z < minZ + BUILD_WIDTH; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    BlockData data = block.getBlockData();
                    Material material = data.getMaterial();
                    if (!material.isItem()) unsupported.add(material);
                    snapshots.add(new BlockSnapshot(x - minX - BUILD_RADIUS, y - minY, z - minZ - BUILD_RADIUS, data.getAsString()));
                }
            }
        }
        if (snapshots.isEmpty()) throw new IllegalArgumentException("选区中没有任何非空气方块。");
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("选区包含不能直接以同种物品发放的方块：" + unsupported + "。请使用普通可放置方块。");
        }
        return new BuildData(key, difficulty, maxY - minY + 1, snapshots);
    }

    private void handleDelBuild(CommandSender sender, String[] args) {
        if (args.length != 2) {
            usage(sender, "/sb delbuild <name>");
            return;
        }
        String key = validKey(args[1], "建筑名称");
        if (builds.remove(key) == null) throw new IllegalArgumentException("不存在名为 " + key + " 的建筑。");
        saveState();
        send(sender, ChatColor.GREEN + "已删除建筑 " + key + "。");
    }

    private void handleListBuild(CommandSender sender) {
        if (builds.isEmpty()) {
            send(sender, ChatColor.YELLOW + "建筑库为空。");
            return;
        }
        send(sender, ChatColor.AQUA + "建筑库（" + builds.size() + "）：");
        List<BuildData> list = new ArrayList<>(builds.values());
        list.sort(Comparator.comparing(build -> build.name));
        for (BuildData build : list) {
            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + build.name + ChatColor.GRAY + " | 难度 " + build.difficulty + " | " + build.blocks.size() + " 方块 | 高 " + build.height);
        }
    }

    private void handleAdd(Player player, String[] args) {
        if (args.length < 2) {
            returnUsage(player, "/sb add <setspawn|island|centre|deathspawn> ...");
            return;
        }
        Arena arena = requireEditorArena(player);
        String type = args[1].toLowerCase(Locale.ROOT);
        switch (type) {
            case "setspawn" -> {
                if (args.length != 3) {
                    returnUsage(player, "/sb add setspawn <1-8>");
                    return;
                }
                int slot = slot(args[2]);
                arena.spawns.put(slot, Point.from(player.getLocation()));
                arena.anchors.put(slot, anchorUnderPlayer(player));
                saveState();
                send(player, ChatColor.GREEN + "已设置玩家 " + slot + " 的出生点及 7×7 建筑基准中心。");
            }
            case "island" -> {
                if (args.length != 7) {
                    returnUsage(player, "/sb addisland <x1> <z1> <x2> <z2> <1-8>");
                    return;
                }
                handleAddIsland(player, new String[]{"addisland", args[2], args[3], args[4], args[5], args[6]});
            }
            case "centre", "center" -> {
                if (args.length != 2) {
                    returnUsage(player, "/sb add centre");
                    return;
                }
                arena.centreAnchor = anchorUnderPlayer(player);
                arena.centrePoint = Point.from(player.getLocation());
                saveState();
                send(player, ChatColor.GREEN + "已设置中心岛建筑基准中心。");
            }
            case "deathspawn" -> {
                if (args.length != 2) {
                    returnUsage(player, "/sb add deathspawn");
                    return;
                }
                arena.deathSpawn = Point.from(player.getLocation());
                saveState();
                send(player, ChatColor.GREEN + "已设置淘汰后的复活/观战点。");
            }
            default -> returnUsage(player, "/sb add <setspawn|island|centre|deathspawn> ...");
        }
    }

    private void handleAddIsland(Player player, String[] args) {
        if (args.length != 6) {
            returnUsage(player, "/sb addisland <x1> <z1> <x2> <z2> <1-8>");
            return;
        }
        Arena arena = requireEditorArena(player);
        int x1 = Integer.parseInt(args[1]);
        int z1 = Integer.parseInt(args[2]);
        int x2 = Integer.parseInt(args[3]);
        int z2 = Integer.parseInt(args[4]);
        int slot = slot(args[5]);
        arena.islands.put(slot, new Bounds2D(x1, z1, x2, z2));
        saveState();
        send(player, ChatColor.GREEN + "已设置玩家 " + slot + " 的岛屿边界。");
    }

    private void handleDel(Player player, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("setspawn")) {
            returnUsage(player, "/sb del setspawn <1-8>");
            return;
        }
        Arena arena = requireEditorArena(player);
        int slot = slot(args[2]);
        arena.spawns.remove(slot);
        arena.anchors.remove(slot);
        arena.islands.remove(slot);
        saveState();
        send(player, ChatColor.GREEN + "已删除玩家 " + slot + " 的出生点、基准点与岛屿配置。");
    }

    private void handleRegister(CommandSender sender, String[] args, boolean register) {
        if (args.length != 2) {
            usage(sender, register ? "/sb register <地图名称>" : "/sb unregister <地图名称>");
            return;
        }
        Arena arena = requireArena(validKey(args[1], "地图名称"));
        requireIdleForEditing(arena);
        if (register) {
            String problem = arena.configurationProblem();
            if (problem != null) throw new IllegalArgumentException("无法注册：" + problem);
            if (builds.isEmpty()) throw new IllegalArgumentException("无法注册：建筑库为空。先使用 /sb addbuild 添加至少一个建筑。");
        }
        arena.registered = register;
        saveState();
        send(sender, register ? ChatColor.GREEN + "竞技场已注册，可供玩家加入。" : ChatColor.YELLOW + "竞技场已取消注册，玩家无法再加入。");
    }

    private void handleEdit(Player player, String[] args) {
        if (args.length != 2) {
            returnUsage(player, "/sb edit <地图名称>");
            return;
        }
        Arena arena = requireArena(validKey(args[1], "地图名称"));
        requireIdleForEditing(arena);
        World world = requireWorld(arena);
        player.teleport(world.getSpawnLocation());
        player.setGameMode(GameMode.CREATIVE);
        disableFlight(player);
        send(player, ChatColor.GREEN + "已进入竞技场编辑模式。完成后使用 /sb save " + arena.name + " 保存，/sb quit 返回生存主世界。");
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "==== MemorySpeedBuild 命令 ====");
        sender.sendMessage(ChatColor.WHITE + "/sb join <地图>" + ChatColor.GRAY + "、/sb quit");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.WHITE + "/sb set survival");
            sender.sendMessage(ChatColor.WHITE + "/sb create template <名称>" + ChatColor.GRAY + "、/sb save template <名称>" + ChatColor.GRAY + "、/sb leave template");
            sender.sendMessage(ChatColor.WHITE + "/sb create map <名称>" + ChatColor.GRAY + "、/sb edit <名称>" + ChatColor.GRAY + "、/sb save <名称>");
            sender.sendMessage(ChatColor.WHITE + "/sb addbuild ... <名称> <1|2|3>" + ChatColor.GRAY + "、/sb delbuild <名称>" + ChatColor.GRAY + "、/sb listbuild");
            sender.sendMessage(ChatColor.WHITE + "/sb add setspawn <1-8>" + ChatColor.GRAY + "、/sb addisland <x1> <z1> <x2> <z2> <1-8>");
            sender.sendMessage(ChatColor.WHITE + "/sb add centre" + ChatColor.GRAY + "、/sb add deathspawn" + ChatColor.GRAY + "、/sb del setspawn <1-8>");
            sender.sendMessage(ChatColor.WHITE + "/sb register <名称>" + ChatColor.GRAY + "、/sb unregister <名称>" + ChatColor.GRAY + "、/sbforce <start|stop>");
        }
    }

    private void returnUsage(Player player, String text) {
        usage(player, text);
    }

    private boolean usage(CommandSender sender, String text) {
        send(sender, ChatColor.YELLOW + "用法：" + text);
        return true;
    }

    // -------------------------------------------------------------------------
    // Arena joining, quitting, and game state
    // -------------------------------------------------------------------------

    private void joinArena(Player player, String rawKey) {
        String key;
        try {
            key = validKey(rawKey, "地图名称");
        } catch (IllegalArgumentException ex) {
            send(player, ChatColor.RED + ex.getMessage());
            return;
        }
        if (arenaOf(player.getUniqueId()) != null) {
            send(player, ChatColor.RED + "你已经在一个速建竞技场中。请先使用 /sb quit。");
            return;
        }
        Arena arena = arenas.get(key);
        if (arena == null || !arena.registered) {
            send(player, ChatColor.RED + "该竞技场不存在或未注册。");
            return;
        }
        if (arena.phase.isGame()) {
            send(player, ChatColor.RED + "该局游戏正在进行，无法加入。");
            return;
        }
        if (arena.phase == Phase.RECOVERING) {
            send(player, ChatColor.RED + "该竞技场正在恢复地图，请稍后再试。");
            return;
        }
        if (arena.players.size() >= MAX_PLAYERS) {
            send(player, ChatColor.RED + "该局游戏人数已满（8/8）。");
            return;
        }
        int slot = randomFreeSlot(arena);
        if (slot == -1) {
            send(player, ChatColor.RED + "该竞技场没有可用出生点。请联系管理员。");
            return;
        }
        World world = requireWorld(arena);
        Point spawn = arena.spawns.get(slot);
        clearInventory(player);
        player.setGameMode(GameMode.ADVENTURE);
        disableFlight(player);
        player.teleport(spawn.toLocation(world));
        arena.players.put(player.getUniqueId(), new PlayerState(player.getUniqueId(), player.getName(), slot));
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        updateSidebars(arena);
        send(player, ChatColor.GREEN + "已加入竞技场 " + arena.name + "，你的出生位是 " + slot + "。");
        broadcast(arena, ChatColor.YELLOW + player.getName() + " 加入了游戏（" + arena.players.size() + "/" + MAX_PLAYERS + "）。");
        if (arena.players.size() >= 4 && arena.phase == Phase.IDLE) startCountdown(arena, false);
    }

    private void quitArena(Player player, boolean announce) {
        Arena arena = arenaOf(player.getUniqueId());
        if (arena == null) {
            returnToSurvival(player, true);
            send(player, ChatColor.YELLOW + "你当前不在速建竞技场，已回到生存主世界。");
            return;
        }
        PlayerState state = arena.players.get(player.getUniqueId());
        if (arena.phase == Phase.IDLE || arena.phase == Phase.COUNTDOWN) {
            arena.players.remove(player.getUniqueId());
            if (arena.players.size() < 4 && arena.phase == Phase.COUNTDOWN && !arena.forcedCountdown) cancelCountdown(arena, "人数不足 4 人，倒计时已取消。");
            returnToSurvival(player, true);
            updateSidebars(arena);
            if (announce) broadcast(arena, ChatColor.YELLOW + player.getName() + " 离开了等待队列。");
            return;
        }
        if (state != null && state.alive) {
            markEliminated(arena, state, ChatColor.YELLOW + player.getName() + " 离开了比赛，判定淘汰。", false);
            clearWholeIsland(arena, state.slot);
            playToArena(arena, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        }
        arena.players.remove(player.getUniqueId());
        spectatorRejoinArena.remove(player.getUniqueId());
        returnToSurvival(player, true);
        updateSidebars(arena);
        checkWinAfterRemoval(arena);
    }

    private void startCountdown(Arena arena, boolean forced) {
        if (arena.phase == Phase.COUNTDOWN) return;
        arena.phase = Phase.COUNTDOWN;
        arena.secondsLeft = COUNTDOWN_SECONDS;
        arena.forcedCountdown = forced;
        arena.session++;
        broadcast(arena, ChatColor.GOLD + "游戏将在 " + COUNTDOWN_SECONDS + " 秒后开始！");
        updateSidebars(arena);
    }

    private void cancelCountdown(Arena arena, String reason) {
        arena.phase = Phase.IDLE;
        arena.secondsLeft = 0;
        arena.forcedCountdown = false;
        broadcast(arena, ChatColor.YELLOW + reason);
        updateSidebars(arena);
    }

    private void beginGame(Arena arena) {
        if (arena.players.isEmpty()) {
            cancelCountdown(arena, "没有玩家，倒计时已取消。");
            return;
        }
        if (builds.isEmpty()) {
            forceStop(arena, ChatColor.RED + "建筑库为空，比赛无法开始。");
            return;
        }
        String problem = arena.configurationProblem();
        if (problem != null) {
            forceStop(arena, ChatColor.RED + "竞技场配置不完整：" + problem);
            return;
        }
        World world = requireWorld(arena);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        arena.phase = Phase.MEMORY;
        arena.round = 1;
        arena.eliminationOrder.clear();
        arena.session++;
        for (PlayerState state : arena.players.values()) {
            state.alive = true;
            state.completed = false;
            state.score = 0;
        }
        broadcast(arena, ChatColor.GREEN + "比赛正式开始！");
        beginMemory(arena);
    }

    private void beginMemory(Arena arena) {
        if (activeStates(arena).size() <= 1) {
            finishGame(arena);
            return;
        }
        arena.phase = Phase.MEMORY;
        arena.secondsLeft = MEMORY_SECONDS;
        arena.currentBuild = randomBuild();
        for (PlayerState state : activeStates(arena)) {
            Player player = Bukkit.getPlayer(state.uuid);
            clearTargetArea(arena, state.slot);
            pasteBuild(arena, state.slot, arena.currentBuild);
            state.completed = false;
            if (player != null && player.isOnline()) {
                clearInventory(player);
                player.setGameMode(GameMode.SURVIVAL);
                enableFlight(player);
                player.removePotionEffect(PotionEffectType.GLOWING);
                title(player, ChatColor.AQUA + "记忆阶段", ChatColor.WHITE + arena.currentBuild.name, 35);
            }
        }
        updateSidebars(arena);
        broadcast(arena, ChatColor.AQUA + "第 " + arena.round + " 回合：请记住建筑 " + ChatColor.WHITE + arena.currentBuild.name + ChatColor.AQUA + "！");
    }

    private void beginRestore(Arena arena) {
        arena.phase = Phase.RESTORE;
        arena.secondsLeft = restoreSeconds(arena.round);
        for (PlayerState state : activeStates(arena)) {
            Player player = Bukkit.getPlayer(state.uuid);
            clearTargetArea(arena, state.slot);
            state.completed = false;
            if (player != null && player.isOnline()) {
                clearInventory(player);
                giveMaterials(player, arena.currentBuild);
                player.setGameMode(GameMode.SURVIVAL);
                enableFlight(player);
                title(player, ChatColor.GREEN + "复原阶段", ChatColor.WHITE + "开始搭建！", 25);
            }
        }
        updateSidebars(arena);
        broadcast(arena, ChatColor.GREEN + "复原开始！限时 " + arena.secondsLeft + " 秒。材料已发放。");
    }

    private void playerCompleted(Arena arena, PlayerState state) {
        if (state.completed || arena.phase != Phase.RESTORE) return;
        state.completed = true;
        Player player = Bukkit.getPlayer(state.uuid);
        int spent = Math.max(0, restoreSeconds(arena.round) - arena.secondsLeft);
        if (player != null && player.isOnline()) {
            title(player, ChatColor.GOLD + "完美复原！", "", 40);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            player.setGameMode(GameMode.ADVENTURE);
            disableFlight(player);
        }
        broadcast(arena, ChatColor.YELLOW + state.name + " 在 " + spent + " 秒内还原了建筑！");
        playToArena(arena, Sound.BLOCK_ANVIL_LAND, 0.7f, 1.3f);
        updateSidebars(arena);
        if (activeStates(arena).stream().allMatch(other -> other.completed)) {
            beginPerfectJudging(arena);
        }
    }

    private void beginPerfectJudging(Arena arena) {
        if (arena.phase != Phase.RESTORE) return;
        arena.phase = Phase.JUDGING;
        final int session = arena.session;
        for (PlayerState state : activeStates(arena)) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) {
                player.setGameMode(GameMode.SURVIVAL);
                enableFlight(player);
                title(player, ChatColor.GREEN + "审视者对你印象不错", "", 60);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
            }
        }
        broadcast(arena, ChatColor.GREEN + "所有玩家均完美复原，跳过评判！");
        updateSidebars(arena);
        later(arena, session, 60L, () -> nextRound(arena));
    }

    private void beginJudging(Arena arena) {
        if (arena.phase != Phase.RESTORE) return;
        arena.phase = Phase.JUDGING;
        final int session = arena.session;
        List<PlayerState> active = activeStates(arena);
        if (active.size() <= 1) {
            finishGame(arena);
            return;
        }

        for (PlayerState state : active) {
            state.score = score(arena, state.slot, arena.currentBuild);
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) {
                player.setGameMode(GameMode.SURVIVAL);
                enableFlight(player);
                title(player, ChatColor.RED + "TIME'S UP! 时间到!", "", 40);
            }
        }
        clearCentreArea(arena);
        pasteCentreBuild(arena, arena.currentBuild);
        spawnJudge(arena, arena.currentBuild);

        int low = active.stream().mapToInt(state -> state.score).min().orElse(0);
        List<PlayerState> lows = active.stream().filter(state -> state.score == low).toList();
        PlayerState loser = lows.get(RANDOM.nextInt(lows.size()));
        markEliminated(arena, loser, null, false);
        arena.judgedLoser = loser.uuid;
        updateSidebars(arena);

        later(arena, session, 40L, () -> {
            for (PlayerState state : active) {
                Player player = Bukkit.getPlayer(state.uuid);
                if (player != null && player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 140, 0, false, false, false));
                    title(player, ChatColor.AQUA + "审视者正在做出鉴定", "", 60);
                }
            }
        });
        later(arena, session, 100L, () -> {
            for (PlayerState state : active) {
                Player player = Bukkit.getPlayer(state.uuid);
                if (player != null && player.isOnline()) {
                    title(player, ChatColor.YELLOW + "你获得 " + state.score + " 分", "", 60);
                }
            }
        });
        later(arena, session, 160L, () -> {
            for (PlayerState state : active) {
                Player player = Bukkit.getPlayer(state.uuid);
                if (player != null && player.isOnline()) {
                    title(player, ChatColor.RED + loser.name + " 淘汰！", "", 60);
                }
            }
            drawJudgeBeam(arena, loser.slot, session);
        });
        later(arena, session, 220L, () -> finishJudgedElimination(arena, loser));
    }

    private void finishJudgedElimination(Arena arena, PlayerState loser) {
        removeJudge(arena);
        clearWholeIsland(arena, loser.slot);
        arena.judgedLoser = null;
        Player player = Bukkit.getPlayer(loser.uuid);
        if (player != null && player.isOnline()) moveToSpectator(arena, player);
        broadcast(arena, ChatColor.RED + loser.name + " 被淘汰了！");
        playToArena(arena, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        updateSidebars(arena);
        checkWinAfterRemoval(arena);
    }

    private void nextRound(Arena arena) {
        if (!arena.phase.isGame()) return;
        if (activeStates(arena).size() <= 1) {
            finishGame(arena);
            return;
        }
        arena.round++;
        beginMemory(arena);
    }

    private void checkWinAfterRemoval(Arena arena) {
        if (!arena.phase.isGame()) return;
        if (activeStates(arena).size() <= 1) {
            finishGame(arena);
        } else if (arena.phase == Phase.RESTORE && activeStates(arena).stream().allMatch(state -> state.completed)) {
            beginPerfectJudging(arena);
        } else if (arena.phase == Phase.JUDGING && arena.judgedLoser == null) {
            nextRound(arena);
        }
    }

    private void finishGame(Arena arena) {
        if (arena.phase == Phase.RECOVERING) return;
        arena.session++;
        arena.phase = Phase.RECOVERING;
        removeJudge(arena);
        List<PlayerState> alive = activeStates(arena);
        String winner = alive.isEmpty() ? null : alive.getFirst().name;
        List<String> podium = podium(arena, winner);

        if (winner != null) {
            broadcast(arena, ChatColor.GOLD + "比赛结束！" + winner + " 赢得了比赛！");
        } else {
            broadcast(arena, ChatColor.YELLOW + "比赛结束，没有产生获胜者。");
        }
        if (!podium.isEmpty()) {
            broadcast(arena, ChatColor.AQUA + "前三名：" + String.join(ChatColor.GRAY + "、" + ChatColor.WHITE, podium));
        }
        for (PlayerState state : arena.players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) {
                title(player, ChatColor.GOLD + (winner == null ? "比赛结束" : winner + " 赢得了比赛！"), "", 60);
            }
        }

        cleanupArenaBuildPlanes(arena);
        for (PlayerState state : new ArrayList<>(arena.players.values())) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) returnToSurvival(player, true);
        }
        for (PlayerState state : arena.players.values()) spectatorRejoinArena.remove(state.uuid);
        arena.players.clear();
        arena.currentBuild = null;
        arena.judgedLoser = null;
        arena.round = 0;
        World world = arena.world();
        if (world != null) {
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.save();
        }
        arena.phase = Phase.IDLE;
        saveState();
    }

    private void forceStop(Arena arena, String reason) {
        arena.session++;
        arena.phase = Phase.RECOVERING;
        removeJudge(arena);
        broadcast(arena, reason);
        cleanupArenaBuildPlanes(arena);
        for (PlayerState state : new ArrayList<>(arena.players.values())) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) returnToSurvival(player, true);
        }
        for (PlayerState state : arena.players.values()) spectatorRejoinArena.remove(state.uuid);
        arena.players.clear();
        arena.currentBuild = null;
        arena.judgedLoser = null;
        arena.round = 0;
        World world = arena.world();
        if (world != null) world.setGameRule(GameRule.KEEP_INVENTORY, true);
        arena.phase = Phase.IDLE;
        saveState();
    }

    private void markEliminated(Arena arena, PlayerState state, String message, boolean spectatorNow) {
        if (state == null || !state.alive) return;
        state.alive = false;
        state.completed = false;
        arena.eliminationOrder.add(state.name);
        spectatorRejoinArena.put(state.uuid, arena.name);
        if (message != null) broadcast(arena, message);
        if (spectatorNow) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) moveToSpectator(arena, player);
        }
        updateSidebars(arena);
    }

    private void moveToSpectator(Arena arena, Player player) {
        World world = requireWorld(arena);
        Point point = arena.deathSpawn != null ? arena.deathSpawn : arena.centrePoint;
        if (point != null) player.teleport(point.toLocation(world));
        clearInventory(player);
        player.setGameMode(GameMode.SPECTATOR);
        disableFlight(player);
    }

    private void returnToSurvival(Player player, boolean clear) {
        if (clear) clearInventory(player);
        player.removePotionEffect(PotionEffectType.GLOWING);
        if (Bukkit.getScoreboardManager() != null) player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.setGameMode(GameMode.SURVIVAL);
        disableFlight(player);
        if (survivalPoint != null) {
            World world = Bukkit.getWorld(survivalPoint.worldName);
            if (world != null) {
                player.teleport(survivalPoint.toLocation(world));
                return;
            }
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (fallback != null) player.teleport(fallback.getSpawnLocation());
    }

    // -------------------------------------------------------------------------
    // Timers and game enforcement
    // -------------------------------------------------------------------------

    private void gameTick() {
        for (Arena arena : arenas.values()) {
            if (arena.phase == Phase.MEMORY) {
                for (PlayerState state : activeStates(arena)) {
                    Player player = Bukkit.getPlayer(state.uuid);
                    if (player != null && player.isOnline()) {
                        enforceIslandBounds(arena, state, player, null);
                        if (!matchesBuild(arena, state.slot, arena.currentBuild)) pasteBuild(arena, state.slot, arena.currentBuild);
                    }
                }
            } else if (arena.phase == Phase.RESTORE) {
                for (PlayerState state : activeStates(arena)) {
                    Player player = Bukkit.getPlayer(state.uuid);
                    if (player != null && player.isOnline()) {
                        enforceIslandBounds(arena, state, player, null);
                        if (!state.completed && matchesBuild(arena, state.slot, arena.currentBuild)) playerCompleted(arena, state);
                    }
                }
            } else if (arena.phase == Phase.JUDGING) {
                for (PlayerState state : activeStates(arena)) {
                    Player player = Bukkit.getPlayer(state.uuid);
                    if (player != null && player.isOnline()) enforceIslandBounds(arena, state, player, null);
                }
            }
        }
    }

    private void secondTick() {
        for (Arena arena : arenas.values()) {
            switch (arena.phase) {
                case COUNTDOWN -> {
                    if (!arena.forcedCountdown && arena.players.size() < 4) {
                        cancelCountdown(arena, "人数不足 4 人，倒计时已取消。");
                        continue;
                    }
                    arena.secondsLeft--;
                    if (arena.secondsLeft == 10 || arena.secondsLeft <= 5 && arena.secondsLeft > 0) {
                        broadcast(arena, ChatColor.GOLD + "游戏将在 " + arena.secondsLeft + " 秒后开始！");
                        playToArena(arena, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.3f);
                    }
                    updateSidebars(arena);
                    if (arena.secondsLeft <= 0) beginGame(arena);
                }
                case MEMORY -> {
                    arena.secondsLeft--;
                    if (arena.secondsLeft <= 5 && arena.secondsLeft > 0) {
                        for (PlayerState state : activeStates(arena)) {
                            Player player = Bukkit.getPlayer(state.uuid);
                            if (player != null && player.isOnline()) {
                                title(player, ChatColor.YELLOW + String.valueOf(arena.secondsLeft), "", 20);
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.3f);
                            }
                        }
                    }
                    updateSidebars(arena);
                    if (arena.secondsLeft <= 0) beginRestore(arena);
                }
                case RESTORE -> {
                    arena.secondsLeft--;
                    updateSidebars(arena);
                    if (arena.secondsLeft <= 0) beginJudging(arena);
                }
                default -> {
                    // no periodic work
                }
            }
        }
    }

    private void later(Arena arena, int session, long ticks, Runnable runnable) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (arena.session == session && arena.phase.isGame()) runnable.run();
        }, ticks);
    }

    private void enforceIslandBounds(Arena arena, PlayerState state, Player player, Location attempted) {
        Bounds2D bounds = arena.islands.get(state.slot);
        if (bounds == null) return;
        Location location = attempted == null ? player.getLocation() : attempted;
        if (location.getWorld() == null || !location.getWorld().getName().equals(arena.worldName)) return;
        if (bounds.contains(location.getX(), location.getZ())) return;
        Anchor anchor = arena.anchors.get(state.slot);
        if (anchor == null) return;
        World world = requireWorld(arena);
        player.teleport(new Location(world, anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch()));
        send(player, ChatColor.RED + "不能离开自己的岛屿范围。");
    }

    private int restoreSeconds(int round) {
        return Math.max(15, 60 - 5 * ((round - 1) / 5));
    }

    // -------------------------------------------------------------------------
    // Block snapshots, material issue, scoring, cleanup
    // -------------------------------------------------------------------------

    private BuildData randomBuild() {
        List<BuildData> list = new ArrayList<>(builds.values());
        return list.get(RANDOM.nextInt(list.size()));
    }

    private void pasteBuild(Arena arena, int slot, BuildData build) {
        Anchor anchor = arena.anchors.get(slot);
        if (anchor == null || build == null) return;
        World world = requireWorld(arena);
        clearTargetArea(arena, slot);
        int baseY = anchor.y + 1;
        for (BlockSnapshot snapshot : build.blocks) {
            Block block = world.getBlockAt(anchor.x + snapshot.dx, baseY + snapshot.dy, anchor.z + snapshot.dz);
            block.setBlockData(Bukkit.createBlockData(snapshot.blockData), false);
        }
    }

    private void pasteCentreBuild(Arena arena, BuildData build) {
        if (arena.centreAnchor == null || build == null) return;
        World world = requireWorld(arena);
        int baseY = arena.centreAnchor.y + 1;
        for (BlockSnapshot snapshot : build.blocks) {
            world.getBlockAt(arena.centreAnchor.x + snapshot.dx, baseY + snapshot.dy, arena.centreAnchor.z + snapshot.dz)
                    .setBlockData(Bukkit.createBlockData(snapshot.blockData), false);
        }
    }

    private void clearTargetArea(Arena arena, int slot) {
        Anchor anchor = arena.anchors.get(slot);
        if (anchor == null) return;
        World world = requireWorld(arena);
        clearSevenBySeven(world, anchor);
    }

    private void clearCentreArea(Arena arena) {
        if (arena.centreAnchor != null) clearSevenBySeven(requireWorld(arena), arena.centreAnchor);
    }

    private void clearSevenBySeven(World world, Anchor anchor) {
        int startY = Math.max(world.getMinHeight(), anchor.y + 1);
        for (int y = startY; y < world.getMaxHeight(); y++) {
            for (int x = anchor.x - BUILD_RADIUS; x <= anchor.x + BUILD_RADIUS; x++) {
                for (int z = anchor.z - BUILD_RADIUS; z <= anchor.z + BUILD_RADIUS; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void clearWholeIsland(Arena arena, int slot) {
        Bounds2D bounds = arena.islands.get(slot);
        if (bounds == null) return;
        World world = requireWorld(arena);
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void cleanupArenaBuildPlanes(Arena arena) {
        for (Integer slot : arena.anchors.keySet()) clearTargetArea(arena, slot);
        clearCentreArea(arena);
    }

    private boolean matchesBuild(Arena arena, int slot, BuildData build) {
        return scoreDetail(arena, slot, build).perfect;
    }

    private int score(Arena arena, int slot, BuildData build) {
        return scoreDetail(arena, slot, build).score;
    }

    private MatchResult scoreDetail(Arena arena, int slot, BuildData build) {
        Anchor anchor = arena.anchors.get(slot);
        if (anchor == null || build == null) return new MatchResult(0, false);
        World world = requireWorld(arena);
        int baseY = anchor.y + 1;
        int correct = 0;
        int wrong = 0;

        for (int dy = 0; dy < build.height; dy++) {
            for (int dx = -BUILD_RADIUS; dx <= BUILD_RADIUS; dx++) {
                for (int dz = -BUILD_RADIUS; dz <= BUILD_RADIUS; dz++) {
                    String expected = build.expected.get(BuildData.positionKey(dx, dy, dz));
                    Block block = world.getBlockAt(anchor.x + dx, baseY + dy, anchor.z + dz);
                    if (expected == null) {
                        if (!block.getType().isAir()) wrong++;
                    } else if (block.getBlockData().getAsString().equals(expected)) {
                        correct++;
                    } else {
                        wrong++;
                    }
                }
            }
        }
        // Extra blocks above the selected source height are invalid too.
        for (int y = baseY + build.height; y < world.getMaxHeight(); y++) {
            for (int x = anchor.x - BUILD_RADIUS; x <= anchor.x + BUILD_RADIUS; x++) {
                for (int z = anchor.z - BUILD_RADIUS; z <= anchor.z + BUILD_RADIUS; z++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) wrong++;
                }
            }
        }
        boolean perfect = correct == build.blocks.size() && wrong == 0;
        int denominator = Math.max(1, correct + wrong);
        int value = (int) Math.floor(100.0 * correct / denominator);
        return new MatchResult(value, perfect);
    }

    private void giveMaterials(Player player, BuildData build) {
        Map<Material, Integer> required = new LinkedHashMap<>();
        for (BlockSnapshot snapshot : build.blocks) {
            Material material = Bukkit.createBlockData(snapshot.blockData).getMaterial();
            required.merge(material, 1, Integer::sum);
        }
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                int stack = Math.min(remaining, entry.getKey().getMaxStackSize());
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(entry.getKey(), stack));
                for (ItemStack item : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                remaining -= stack;
            }
        }
    }

    private void spawnJudge(Arena arena, BuildData build) {
        removeJudge(arena);
        if (arena.centreAnchor == null) return;
        World world = requireWorld(arena);
        Anchor anchor = arena.centreAnchor;
        Location at = new Location(world, anchor.x + 0.5, anchor.y + build.height + 2.0, anchor.z + 0.5);
        Entity entity = world.spawnEntity(at, org.bukkit.entity.EntityType.ELDER_GUARDIAN);
        if (entity instanceof Guardian guardian) {
            guardian.setCustomName(ChatColor.AQUA + "审视者");
            guardian.setCustomNameVisible(true);
            guardian.setAI(false);
            guardian.setInvulnerable(true);
            guardian.setSilent(true);
            guardian.setGravity(false);
            arena.judge = guardian.getUniqueId();
        } else {
            entity.remove();
        }
    }

    private void drawJudgeBeam(Arena arena, int slot, int session) {
        if (arena.judge == null) return;
        Entity entity = Bukkit.getEntity(arena.judge);
        Anchor target = arena.anchors.get(slot);
        if (entity == null || target == null) return;
        Location start = entity.getLocation().add(0.0, 1.0, 0.0);
        Location end = new Location(requireWorld(arena), target.x + 0.5, target.y + 1.0, target.z + 0.5);
        for (int repeat = 0; repeat < 6; repeat++) {
            int delay = repeat * 8;
            later(arena, session, delay, () -> {
                Entity current = Bukkit.getEntity(arena.judge);
                if (current == null) return;
                for (int i = 0; i <= 30; i++) {
                    double progress = i / 30.0;
                    Location point = start.clone().multiply(1.0 - progress).add(end.clone().multiply(progress));
                    start.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
                }
            });
        }
    }

    private void removeJudge(Arena arena) {
        if (arena.judge != null) {
            Entity entity = Bukkit.getEntity(arena.judge);
            if (entity != null) entity.remove();
            arena.judge = null;
        }
    }

    // -------------------------------------------------------------------------
    // Event protection and player lifecycle
    // -------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena == null) return;
        if (!canModify(arena, event.getPlayer(), event.getBlockPlaced().getX(), event.getBlockPlaced().getZ())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena == null) return;
        if (!canModify(arena, event.getPlayer(), event.getBlock().getX(), event.getBlock().getZ())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Arena arena = arenaByWorld(event.getBlock().getWorld());
        if (arena != null && arena.phase.isGame()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Arena arena = arenaByWorld(event.getBlock().getWorld());
        if (arena != null && arena.phase.isGame()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Arena arena = arenaByWorld(event.getLocation().getWorld());
        if (arena != null && arena.phase.isGame()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Arena arena = arenaByWorld(event.getBlock().getWorld());
        if (arena != null && arena.phase.isGame()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena != null && arena.phase.isGame()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena == null || !arena.phase.isGame() || event.getTo() == null) return;
        PlayerState state = arena.players.get(event.getPlayer().getUniqueId());
        if (state != null && state.alive) enforceIslandBounds(arena, state, event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena == null || !arena.phase.isGame()) return;
        PlayerState state = arena.players.get(event.getPlayer().getUniqueId());
        if (state != null && state.alive) {
            event.setCancelled(false);
            event.getPlayer().setAllowFlight(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Arena arena = arenaOf(event.getEntity().getUniqueId());
        if (arena == null || !arena.phase.isGame()) return;
        PlayerState state = arena.players.get(event.getEntity().getUniqueId());
        if (state == null || !state.alive) return;
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDeathMessage(null);
        markEliminated(arena, state, ChatColor.RED + state.name + " 死亡，判定淘汰。", false);
        clearWholeIsland(arena, state.slot);
        playToArena(arena, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        checkWinAfterRemoval(arena);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Arena arena = arenaOf(event.getPlayer().getUniqueId());
        if (arena == null) return;
        PlayerState state = arena.players.get(event.getPlayer().getUniqueId());
        if (state == null || state.alive) return;
        Point target = arena.deathSpawn != null ? arena.deathSpawn : arena.centrePoint;
        if (target != null) event.setRespawnLocation(target.toLocation(requireWorld(arena)));
        Bukkit.getScheduler().runTask(this, () -> moveToSpectator(arena, event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Arena arena = arenaOf(player.getUniqueId());
        if (arena == null) return;
        PlayerState state = arena.players.get(player.getUniqueId());
        if (arena.phase == Phase.IDLE || arena.phase == Phase.COUNTDOWN) {
            arena.players.remove(player.getUniqueId());
            if (!arena.forcedCountdown && arena.phase == Phase.COUNTDOWN && arena.players.size() < 4) {
                cancelCountdown(arena, "人数不足 4 人，倒计时已取消。");
            }
            updateSidebars(arena);
            return;
        }
        if (state != null && state.alive) {
            markEliminated(arena, state, ChatColor.RED + state.name + " 中途离线，判定淘汰。", false);
            clearWholeIsland(arena, state.slot);
            playToArena(arena, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
            checkWinAfterRemoval(arena);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String arenaName = spectatorRejoinArena.get(event.getPlayer().getUniqueId());
        if (arenaName == null) return;
        Arena arena = arenas.get(arenaName);
        if (arena == null || !arena.phase.isGame()) {
            spectatorRejoinArena.remove(event.getPlayer().getUniqueId());
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> moveToSpectator(arena, event.getPlayer()));
    }

    private boolean canModify(Arena arena, Player player, int x, int z) {
        if (arena.phase != Phase.RESTORE) return false;
        PlayerState state = arena.players.get(player.getUniqueId());
        if (state == null || !state.alive || state.completed) return false;
        Bounds2D bounds = arena.islands.get(state.slot);
        return bounds != null && bounds.contains(x + 0.5, z + 0.5);
    }

    // -------------------------------------------------------------------------
    // Sidebar, utilities and world setup
    // -------------------------------------------------------------------------

    private void updateSidebars(Arena arena) {
        for (PlayerState state : arena.players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player == null || !player.isOnline()) continue;
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = scoreboard.registerNewObjective("sb", "dummy", ChatColor.AQUA + "记忆速建");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            String theme = arena.currentBuild == null ? "等待中" : arena.currentBuild.name;
            String phase = switch (arena.phase) {
                case COUNTDOWN -> "倒计时 " + arena.secondsLeft + "s";
                case MEMORY -> "记忆 " + arena.secondsLeft + "s";
                case RESTORE -> "复原 " + arena.secondsLeft + "s";
                case JUDGING -> "评判中";
                case RECOVERING -> "恢复中";
                default -> "等待中";
            };
            objective.getScore(ChatColor.GRAY + "主题: " + ChatColor.WHITE + trim(theme, 24)).setScore(4);
            objective.getScore(ChatColor.GRAY + "回合: " + ChatColor.WHITE + Math.max(0, arena.round)).setScore(3);
            objective.getScore(ChatColor.GRAY + "玩家: " + ChatColor.WHITE + activeStates(arena).size()).setScore(2);
            objective.getScore(ChatColor.GRAY + "阶段: " + ChatColor.WHITE + phase).setScore(1);
            player.setScoreboard(scoreboard);
        }
    }

    private List<PlayerState> activeStates(Arena arena) {
        return arena.players.values().stream().filter(state -> state.alive).toList();
    }

    private List<String> podium(Arena arena, String winner) {
        List<String> result = new ArrayList<>();
        if (winner != null) result.add(ChatColor.GOLD + "1. " + ChatColor.WHITE + winner);
        List<String> reverse = new ArrayList<>(arena.eliminationOrder);
        Collections.reverse(reverse);
        for (String name : reverse) {
            if (result.size() >= 3) break;
            result.add(ChatColor.YELLOW.toString() + (result.size() + 1) + ". " + ChatColor.WHITE + name);
        }
        return result;
    }

    private int randomFreeSlot(Arena arena) {
        List<Integer> slots = new ArrayList<>();
        for (Map.Entry<Integer, Point> entry : arena.spawns.entrySet()) {
            int slot = entry.getKey();
            if (!arena.anchors.containsKey(slot) || !arena.islands.containsKey(slot)) continue;
            boolean occupied = arena.players.values().stream().anyMatch(state -> state.slot == slot);
            if (!occupied) slots.add(slot);
        }
        return slots.isEmpty() ? -1 : slots.get(RANDOM.nextInt(slots.size()));
    }

    private void enableFlight(Player player) {
        player.setAllowFlight(true);
        player.setFlying(false);
    }

    private void disableFlight(Player player) {
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    private void createPlatform(World world, int centerX, int y, int centerZ) {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                world.getBlockAt(x, y, z).setType(Material.SMOOTH_QUARTZ, false);
            }
        }
    }

    private World createVoidWorld(String worldName) {
        World current = Bukkit.getWorld(worldName);
        if (current != null) return current;
        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(new VoidGenerator());
        creator.generateStructures(false);
        World world = Bukkit.createWorld(creator);
        if (world == null) throw new IllegalArgumentException("创建世界失败：" + worldName);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(6000L);
        world.setStorm(false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        return world;
    }

    private void loadConfiguredWorlds() {
        for (String template : templates) createVoidWorld(templateWorldName(template));
        for (Arena arena : arenas.values()) createVoidWorld(arena.worldName);
    }

    private World requireWorld(Arena arena) {
        World world = arena.world();
        if (world == null) world = createVoidWorld(arena.worldName);
        return world;
    }

    private Arena requireArena(String key) {
        Arena arena = arenas.get(key);
        if (arena == null) throw new IllegalArgumentException("竞技场 " + key + " 不存在。");
        return arena;
    }

    private Arena requireEditorArena(Player player) {
        Arena arena = arenaByWorld(player.getWorld());
        if (arena == null) throw new IllegalArgumentException("请站在需要编辑的竞技场世界内执行该命令。");
        requireIdleForEditing(arena);
        return arena;
    }

    private void requireIdleForEditing(Arena arena) {
        if (arena.phase != Phase.IDLE || !arena.players.isEmpty()) {
            throw new IllegalArgumentException("竞技场正在等待、游戏或恢复中，不能编辑。");
        }
    }

    private Arena arenaByWorld(World world) {
        if (world == null) return null;
        return arenas.values().stream().filter(arena -> arena.worldName.equals(world.getName())).findFirst().orElse(null);
    }

    private Arena arenaOf(UUID uuid) {
        return arenas.values().stream().filter(arena -> arena.players.containsKey(uuid)).findFirst().orElse(null);
    }

    private Arena contextArena(CommandSender sender) {
        if (sender instanceof Player player) {
            Arena at = arenaByWorld(player.getWorld());
            if (at != null) return at;
            Arena joined = arenaOf(player.getUniqueId());
            if (joined != null) return joined;
        }
        List<Arena> candidates = arenas.values().stream().filter(arena -> arena.phase != Phase.IDLE || !arena.players.isEmpty()).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private boolean isTemplateWorld(World world) {
        return world != null && templates.stream().anyMatch(template -> templateWorldName(template).equals(world.getName()));
    }

    private boolean isIsolatedBuildWorld(World world) {
        return isTemplateWorld(world) || arenaByWorld(world) != null;
    }

    private static Anchor anchorUnderPlayer(Player player) {
        Location location = player.getLocation();
        return new Anchor(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
    }

    private static int slot(String raw) {
        int value = Integer.parseInt(raw);
        if (value < 1 || value > MAX_PLAYERS) throw new IllegalArgumentException("玩家编号必须在 1 到 " + MAX_PLAYERS + " 之间。");
        return value;
    }

    private static String validKey(String raw, String label) {
        String key = raw.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(label + "只能使用 1-32 个小写字母、数字、下划线或连字符。");
        }
        return key;
    }

    private static String templateWorldName(String key) {
        return "sb_template_" + key;
    }

    private static String arenaWorldName(String key) {
        return "sb_arena_" + key;
    }

    private static String trim(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static String formatPoint(Point point) {
        return point.worldName + " (" + point.x + ", " + point.y + ", " + point.z + ")";
    }

    private static void title(Player player, String title, String subtitle, int stayTicks) {
        player.sendTitle(title, subtitle, 0, stayTicks, 0);
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + message);
    }

    private static void broadcast(Arena arena, String message) {
        for (PlayerState state : arena.players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) player.sendMessage(PREFIX + message);
        }
    }

    private static void playToArena(Arena arena, Sound sound, float volume, float pitch) {
        World world = arena.world();
        if (world == null) return;
        for (PlayerState state : arena.players.values()) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player != null && player.isOnline()) player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    // -------------------------------------------------------------------------
    // Persistent data
    // -------------------------------------------------------------------------

    private void loadState() {
        arenas.clear();
        builds.clear();
        templates.clear();
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        survivalPoint = readPoint(yaml.getConfigurationSection("survival"));
        templates.addAll(yaml.getStringList("templates"));

        ConfigurationSection arenaSection = yaml.getConfigurationSection("arenas");
        if (arenaSection != null) {
            for (String key : arenaSection.getKeys(false)) {
                ConfigurationSection section = arenaSection.getConfigurationSection(key);
                if (section == null) continue;
                String name = section.getString("name", key);
                String world = section.getString("world", arenaWorldName(key));
                Arena arena = new Arena(name, world);
                arena.registered = section.getBoolean("registered", false);
                arena.centreAnchor = readAnchor(section.getConfigurationSection("centre-anchor"));
                arena.centrePoint = readPoint(section.getConfigurationSection("centre-point"));
                arena.deathSpawn = readPoint(section.getConfigurationSection("death-spawn"));
                ConfigurationSection spawns = section.getConfigurationSection("spawns");
                if (spawns != null) {
                    for (String slot : spawns.getKeys(false)) {
                        try { arena.spawns.put(Integer.parseInt(slot), readPoint(spawns.getConfigurationSection(slot))); } catch (NumberFormatException ignored) { }
                    }
                }
                ConfigurationSection anchors = section.getConfigurationSection("anchors");
                if (anchors != null) {
                    for (String slot : anchors.getKeys(false)) {
                        try { arena.anchors.put(Integer.parseInt(slot), readAnchor(anchors.getConfigurationSection(slot))); } catch (NumberFormatException ignored) { }
                    }
                }
                ConfigurationSection islands = section.getConfigurationSection("islands");
                if (islands != null) {
                    for (String slot : islands.getKeys(false)) {
                        try {
                            ConfigurationSection b = islands.getConfigurationSection(slot);
                            if (b != null) arena.islands.put(Integer.parseInt(slot), new Bounds2D(b.getInt("min-x"), b.getInt("min-z"), b.getInt("max-x"), b.getInt("max-z")));
                        } catch (NumberFormatException ignored) { }
                    }
                }
                arenas.put(key, arena);
            }
        }

        ConfigurationSection buildSection = yaml.getConfigurationSection("builds");
        if (buildSection != null) {
            for (String key : buildSection.getKeys(false)) {
                ConfigurationSection section = buildSection.getConfigurationSection(key);
                if (section == null) continue;
                List<BlockSnapshot> snapshots = new ArrayList<>();
                for (String encoded : section.getStringList("blocks")) {
                    String[] parts = encoded.split(";", 4);
                    if (parts.length != 4) continue;
                    try {
                        snapshots.add(new BlockSnapshot(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]));
                    } catch (NumberFormatException ignored) { }
                }
                if (!snapshots.isEmpty()) {
                    builds.put(key, new BuildData(section.getString("name", key), section.getInt("difficulty", 1), section.getInt("height", 1), snapshots));
                }
            }
        }
    }

    private void saveState() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("无法创建插件数据目录。");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        writePoint(yaml.createSection("survival"), survivalPoint);
        yaml.set("templates", new ArrayList<>(templates));

        ConfigurationSection arenaSection = yaml.createSection("arenas");
        for (Map.Entry<String, Arena> entry : arenas.entrySet()) {
            Arena arena = entry.getValue();
            ConfigurationSection section = arenaSection.createSection(entry.getKey());
            section.set("name", arena.name);
            section.set("world", arena.worldName);
            section.set("registered", arena.registered);
            writeAnchor(section.createSection("centre-anchor"), arena.centreAnchor);
            writePoint(section.createSection("centre-point"), arena.centrePoint);
            writePoint(section.createSection("death-spawn"), arena.deathSpawn);
            ConfigurationSection spawns = section.createSection("spawns");
            for (Map.Entry<Integer, Point> spawn : arena.spawns.entrySet()) writePoint(spawns.createSection(String.valueOf(spawn.getKey())), spawn.getValue());
            ConfigurationSection anchors = section.createSection("anchors");
            for (Map.Entry<Integer, Anchor> anchor : arena.anchors.entrySet()) writeAnchor(anchors.createSection(String.valueOf(anchor.getKey())), anchor.getValue());
            ConfigurationSection islands = section.createSection("islands");
            for (Map.Entry<Integer, Bounds2D> island : arena.islands.entrySet()) {
                ConfigurationSection b = islands.createSection(String.valueOf(island.getKey()));
                b.set("min-x", island.getValue().minX);
                b.set("min-z", island.getValue().minZ);
                b.set("max-x", island.getValue().maxX);
                b.set("max-z", island.getValue().maxZ);
            }
        }

        ConfigurationSection buildSection = yaml.createSection("builds");
        for (Map.Entry<String, BuildData> entry : builds.entrySet()) {
            BuildData build = entry.getValue();
            ConfigurationSection section = buildSection.createSection(entry.getKey());
            section.set("name", build.name);
            section.set("difficulty", build.difficulty);
            section.set("height", build.height);
            List<String> serialized = new ArrayList<>();
            for (BlockSnapshot block : build.blocks) serialized.add(block.dx + ";" + block.dy + ";" + block.dz + ";" + block.blockData);
            section.set("blocks", serialized);
        }

        try {
            yaml.save(stateFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "无法保存 state.yml", ex);
        }
    }

    private static Point readPoint(ConfigurationSection section) {
        if (section == null || !section.contains("x") || !section.contains("world")) return null;
        return new Point(section.getString("world", "world"), section.getDouble("x"), section.getDouble("y"), section.getDouble("z"), (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    private static Anchor readAnchor(ConfigurationSection section) {
        if (section == null || !section.contains("x")) return null;
        return new Anchor(section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private static void writePoint(ConfigurationSection section, Point point) {
        if (point == null) return;
        section.set("world", point.worldName);
        section.set("x", point.x);
        section.set("y", point.y);
        section.set("z", point.z);
        section.set("yaw", point.yaw);
        section.set("pitch", point.pitch);
    }

    private static void writeAnchor(ConfigurationSection section, Anchor anchor) {
        if (anchor == null) return;
        section.set("x", anchor.x);
        section.set("y", anchor.y);
        section.set("z", anchor.z);
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("sbforce")) {
            return args.length == 1 ? filter(args[0], List.of("start", "stop")) : List.of();
        }
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("join", "quit", "help"));
            if (sender.hasPermission(ADMIN_PERMISSION)) base.addAll(List.of("set", "create", "save", "leave", "addbuild", "delbuild", "listbuild", "add", "addisland", "del", "register", "unregister", "edit"));
            return filter(args[0], base);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("join") && args.length == 2) return filter(args[1], arenas.values().stream().filter(arena -> arena.registered).map(arena -> arena.name).toList());
        if ((root.equals("register") || root.equals("unregister") || root.equals("edit")) && args.length == 2) return filter(args[1], new ArrayList<>(arenas.keySet()));
        if (root.equals("create") && args.length == 2) return filter(args[1], List.of("template", "map"));
        if (root.equals("set") && args.length == 2) return filter(args[1], List.of("survival"));
        if (root.equals("leave") && args.length == 2) return filter(args[1], List.of("template"));
        if (root.equals("save") && args.length == 2) {
            List<String> choices = new ArrayList<>(arenas.keySet());
            choices.add("template");
            return filter(args[1], choices);
        }
        if (root.equals("save") && args.length == 3 && args[1].equalsIgnoreCase("template")) return filter(args[2], new ArrayList<>(templates));
        if ((root.equals("delbuild")) && args.length == 2) return filter(args[1], new ArrayList<>(builds.keySet()));
        if (root.equals("add") && args.length == 2) return filter(args[1], List.of("setspawn", "centre", "deathspawn"));
        if (root.equals("del") && args.length == 2) return filter(args[1], List.of("setspawn"));
        return List.of();
    }

    private static List<String> filter(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private enum Phase {
        IDLE, COUNTDOWN, MEMORY, RESTORE, JUDGING, RECOVERING;

        boolean isGame() {
            return this == MEMORY || this == RESTORE || this == JUDGING;
        }
    }

    private static final class Arena {
        final String name;
        final String worldName;
        boolean registered;
        final Map<Integer, Point> spawns = new HashMap<>();
        final Map<Integer, Anchor> anchors = new HashMap<>();
        final Map<Integer, Bounds2D> islands = new HashMap<>();
        Point centrePoint;
        Anchor centreAnchor;
        Point deathSpawn;
        final Map<UUID, PlayerState> players = new LinkedHashMap<>();
        final List<String> eliminationOrder = new ArrayList<>();
        Phase phase = Phase.IDLE;
        boolean forcedCountdown;
        int secondsLeft;
        int round;
        int session;
        BuildData currentBuild;
        UUID judge;
        UUID judgedLoser;

        Arena(String name, String worldName) {
            this.name = name;
            this.worldName = worldName;
        }

        World world() {
            return Bukkit.getWorld(worldName);
        }

        String configurationProblem() {
            if (centreAnchor == null || centrePoint == null) return "未设置中心岛（/sb add centre）";
            if (deathSpawn == null) return "未设置死亡复活点（/sb add deathspawn）";
            if (spawns.isEmpty()) return "未设置任何玩家出生点";
            for (Integer slot : spawns.keySet()) {
                if (!anchors.containsKey(slot)) return "玩家 " + slot + " 缺少建筑基准点";
                if (!islands.containsKey(slot)) return "玩家 " + slot + " 缺少岛屿范围";
            }
            return null;
        }
    }

    private static final class PlayerState {
        final UUID uuid;
        final String name;
        final int slot;
        boolean alive = true;
        boolean completed;
        int score;

        PlayerState(UUID uuid, String name, int slot) {
            this.uuid = uuid;
            this.name = name;
            this.slot = slot;
        }
    }

    private static final class BuildData {
        final String name;
        final int difficulty;
        final int height;
        final List<BlockSnapshot> blocks;
        final Map<Integer, String> expected = new HashMap<>();

        BuildData(String name, int difficulty, int height, List<BlockSnapshot> blocks) {
            this.name = name;
            this.difficulty = difficulty;
            this.height = Math.max(1, height);
            this.blocks = List.copyOf(blocks);
            for (BlockSnapshot block : blocks) expected.put(positionKey(block.dx, block.dy, block.dz), block.blockData);
        }

        static int positionKey(int dx, int dy, int dz) {
            return (dy << 8) ^ ((dx + BUILD_RADIUS) << 4) ^ (dz + BUILD_RADIUS);
        }
    }

    private record BlockSnapshot(int dx, int dy, int dz, String blockData) { }

    private record MatchResult(int score, boolean perfect) { }

    private record Bounds2D(int minX, int minZ, int maxX, int maxZ) {
        Bounds2D {
            int lowX = Math.min(minX, maxX);
            int highX = Math.max(minX, maxX);
            int lowZ = Math.min(minZ, maxZ);
            int highZ = Math.max(minZ, maxZ);
            minX = lowX;
            maxX = highX;
            minZ = lowZ;
            maxZ = highZ;
        }

        boolean contains(double x, double z) {
            return x >= minX && x <= maxX + 1.0 && z >= minZ && z <= maxZ + 1.0;
        }
    }

    private record Anchor(int x, int y, int z) { }

    private record Point(String worldName, double x, double y, double z, float yaw, float pitch) {
        static Point from(Location location) {
            return new Point(Objects.requireNonNull(location.getWorld()).getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }

        Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
