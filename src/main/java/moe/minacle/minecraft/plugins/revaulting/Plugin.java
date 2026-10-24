package moe.minacle.minecraft.plugins.revaulting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.bstats.bukkit.Metrics;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.event.block.VaultChangeStateEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class Plugin extends JavaPlugin implements Listener {

    private static final int BSTATS_PLUGIN_ID = 22418;

    private static int @NotNull [] uuidToIntArray(final @NotNull UUID uuid) {
        return new int[] {
            (int)(uuid.getMostSignificantBits() >> 32),
            (int)uuid.getMostSignificantBits(),
            (int)(uuid.getLeastSignificantBits() >> 32),
            (int)uuid.getLeastSignificantBits(),
        };
    }

    private static @NotNull UUID intArrayToUuid(final int @NotNull [] arr) {
        return new UUID(
            ((long)arr[0] << 32) | (arr[1] & 0xFFFFFFFFL),
            ((long)arr[2] << 32) | (arr[3] & 0xFFFFFFFFL));
    }

    private static @NotNull Map<@NotNull UUID, @NotNull Long> deserializeCooldownMap(final @NotNull List<int @NotNull []> players, final @NotNull List<@NotNull Long> ticks) {
        final Map<UUID, Long> map = new HashMap<>(players.size());
        for (int i = 0; i < players.size(); i++)
            map.put(intArrayToUuid(players.get(i)), i < ticks.size() ? ticks.get(i) : 0L);
        return map;
    }

    private static void serializeCooldownMap(final @NotNull Map<@NotNull UUID, @NotNull Long> map, final @NotNull List<int @NotNull []> playersOut, final @NotNull List<@NotNull Long> ticksOut) {
        for (final var entry : map.entrySet()) {
            playersOut.add(uuidToIntArray(entry.getKey()));
            ticksOut.add(entry.getValue());
        }
    }

    private static @NotNull Map<@NotNull UUID, @NotNull Integer> deserializeRewardedMap(final @NotNull List<int @NotNull []> players, final @NotNull List<@NotNull Integer> counts) {
        final Map<UUID, Integer> map = new HashMap<>(players.size());
        for (int i = 0; i < players.size(); i++)
            map.put(intArrayToUuid(players.get(i)), i < counts.size() ? counts.get(i) : 0);
        return map;
    }

    private static void serializeRewardedMap(final @NotNull Map<@NotNull UUID, @NotNull Integer> map, final @NotNull List<int @NotNull []> playersOut, final @NotNull List<@NotNull Integer> countsOut) {
        for (final var entry : map.entrySet()) {
            playersOut.add(uuidToIntArray(entry.getKey()));
            countsOut.add(entry.getValue());
        }
    }

    private final @NotNull NamespacedKey COOLDOWN_PLAYERS_KEY = Objects.requireNonNull(namespacedKey("cooldown_players"));

    private final @NotNull NamespacedKey COOLDOWN_START_TICKS_KEY = Objects.requireNonNull(namespacedKey("cooldown_start_ticks"));

    private final @NotNull NamespacedKey REWARDED_PLAYERS_KEY = Objects.requireNonNull(namespacedKey("rewarded_players"));

    private final @NotNull NamespacedKey REWARDED_COUNTS_KEY = Objects.requireNonNull(namespacedKey("rewarded_counts"));

    private final @NotNull NamespacedKey UNLOCKING_PLAYER_KEY = Objects.requireNonNull(namespacedKey("unlocking_player"));

    private @Nullable Configuration configuration;

    private @Nullable ScheduledTask task;

    private final @NotNull VaultIndex vaultIndex = new VaultIndex();

    private @Nullable NamespacedKey namespacedKey(final @NotNull String key) {
        return NamespacedKey.fromString(key, this);
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch (final ClassNotFoundException e) {
            return false;
        }
    }

    private static VaultIndex.@NotNull Position position(final @NotNull Block block) {
        return new VaultIndex.Position(block.getX(), block.getY(), block.getZ());
    }

    private void indexChunk(final @NotNull Chunk chunk) {
        final List<VaultIndex.Position> vaults = new ArrayList<>();
        for (final var state : chunk.getTileEntities(block -> block.getType() == Material.VAULT, false))
            vaults.add(new VaultIndex.Position(state.getX(), state.getY(), state.getZ()));
        vaultIndex.replaceChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), vaults);
    }

    private void checkVaultCooldownsNearPlayer(final @NotNull Player player) {
        final Location location;
        final World world;
        final Map<Long, List<VaultIndex.Position>> vaultsByChunk;
        if (!player.isConnected() || player.getGameMode() == GameMode.SPECTATOR)
            return;
        if ((location = player.getLocation()) == null)
            return;
        if ((world = location.getWorld()) == null)
            return;
        vaultsByChunk = vaultIndex.near(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), 3);
        for (final var entry : vaultsByChunk.entrySet()) {
            final long chunkKey = entry.getKey();
            final int chunkX = (int)(chunkKey >> 32);
            final int chunkZ = (int)chunkKey;
            final List<VaultIndex.Position> vaults = entry.getValue();
            getServer().getRegionScheduler().execute(
                this,
                world,
                chunkX,
                chunkZ,
                () -> {
                    for (final var vault : vaults) {
                        final Block block = world.getBlockAt(vault.x(), vault.y(), vault.z());
                        if (block.getType() == Material.VAULT)
                            checkVaultCooldowns(block);
                        else
                            vaultIndex.remove(world.getUID(), vault);
                    }
                });
        }
    }

    private void checkVaultCooldowns(final @NotNull Block block) {
        final long currentTick;
        final org.bukkit.block.Vault vaultState;
        final org.bukkit.block.data.type.Vault vaultBlockData;
        final PersistentDataContainer persistentDataContainer;
        final Map<UUID, Long> cooldownMap;
        final List<UUID> cooldownPlayersToRemove = new ArrayList<>();
        final Collection<UUID> rewardedPlayers;
        final int cooldownTicks;
        if (block.getType() != Material.VAULT)
            return;
        currentTick = block.getWorld().getGameTime();
        vaultState = (org.bukkit.block.Vault)block.getState();
        vaultBlockData = (org.bukkit.block.data.type.Vault)block.getBlockData();
        persistentDataContainer = vaultState.getPersistentDataContainer();
        cooldownMap = deserializeCooldownMap(
            persistentDataContainer.getOrDefault(
                COOLDOWN_PLAYERS_KEY,
                PersistentDataType.LIST.integerArrays(),
                List.of()),
            persistentDataContainer.getOrDefault(
                COOLDOWN_START_TICKS_KEY,
                PersistentDataType.LIST.longs(),
                List.of()));
        rewardedPlayers = vaultState.getRewardedPlayers();
        if (vaultBlockData.isOminous())
            cooldownTicks = configuration.getDelay().getOminousVault();
        else
            cooldownTicks = configuration.getDelay().getVault();
        for (final var player : rewardedPlayers) {
            if (!cooldownMap.containsKey(player))
                cooldownMap.put(player, Long.MIN_VALUE);
        }
        for (final var entry : cooldownMap.entrySet()) {
            final long startTick = entry.getValue();
            final long elapsedTicks;
            if (startTick == Long.MIN_VALUE)
                elapsedTicks = Long.MAX_VALUE;
            else
                elapsedTicks = currentTick - startTick;
            if (elapsedTicks >= cooldownTicks)
                cooldownPlayersToRemove.add(entry.getKey());
        }
        if (!cooldownPlayersToRemove.isEmpty()) {
            final List<int[]> playersOut;
            final List<Long> ticksOut;
            for (final var player : cooldownPlayersToRemove) {
                cooldownMap.remove(player);
                if (rewardedPlayers.contains(player))
                    vaultState.removeRewardedPlayer(player);
            }
            playersOut = new ArrayList<>(cooldownMap.size());
            ticksOut = new ArrayList<>(cooldownMap.size());
            serializeCooldownMap(cooldownMap, playersOut, ticksOut);
            persistentDataContainer.set(COOLDOWN_PLAYERS_KEY, PersistentDataType.LIST.integerArrays(), playersOut);
            persistentDataContainer.set(COOLDOWN_START_TICKS_KEY, PersistentDataType.LIST.longs(), ticksOut);
            vaultState.update();
            for (final var player : cooldownPlayersToRemove)
                registerRewardedPlayer(block, player);
        }
    }

    private void setVaultCooldownStartForPlayer(final @NotNull Block block, final long ticks, final @NotNull UUID player) {
        final org.bukkit.block.Vault vaultState = (org.bukkit.block.Vault)block.getState();
        final PersistentDataContainer persistentDataContainer = vaultState.getPersistentDataContainer();
        final Map<UUID, Long> cooldownMap = deserializeCooldownMap(
            persistentDataContainer.getOrDefault(
                COOLDOWN_PLAYERS_KEY,
                PersistentDataType.LIST.integerArrays(),
                List.of()),
            persistentDataContainer.getOrDefault(
                COOLDOWN_START_TICKS_KEY,
                PersistentDataType.LIST.longs(),
                List.of()));
        final List<int[]> playersOut;
        final List<Long> ticksOut;
        cooldownMap.put(player, ticks);
        playersOut = new ArrayList<>(cooldownMap.size());
        ticksOut = new ArrayList<>(cooldownMap.size());
        serializeCooldownMap(cooldownMap, playersOut, ticksOut);
        persistentDataContainer.set(COOLDOWN_PLAYERS_KEY, PersistentDataType.LIST.integerArrays(), playersOut);
        persistentDataContainer.set(COOLDOWN_START_TICKS_KEY, PersistentDataType.LIST.longs(), ticksOut);
        vaultState.update();
    }

    private void reserveVaultCooldownForPlayer(final @NotNull Block block, final @NotNull UUID player) {
        setVaultCooldownStartForPlayer(block, Long.MAX_VALUE, player);
    }

    private void startVaultCooldownForPlayer(final @NotNull Block block, final @NotNull UUID player) {
        setVaultCooldownStartForPlayer(block, block.getWorld().getGameTime(), player);
    }

    private void registerRewardedPlayer(final @NotNull Block block, final @NotNull UUID player) {
        final org.bukkit.block.Vault vaultState = (org.bukkit.block.Vault)block.getState();
        final PersistentDataContainer persistentDataContainer = vaultState.getPersistentDataContainer();
        final Map<UUID, Integer> rewardedMap = deserializeRewardedMap(
            persistentDataContainer.getOrDefault(
                REWARDED_PLAYERS_KEY,
                PersistentDataType.LIST.integerArrays(),
                List.of()),
            persistentDataContainer.getOrDefault(
                REWARDED_COUNTS_KEY,
                PersistentDataType.LIST.integers(),
                List.of()));
        final List<int[]> playersOut;
        final List<Integer> countsOut;
        rewardedMap.merge(player, 1, (oldVal, newVal) -> oldVal + newVal);
        playersOut = new ArrayList<>(rewardedMap.size());
        countsOut = new ArrayList<>(rewardedMap.size());
        serializeRewardedMap(rewardedMap, playersOut, countsOut);
        persistentDataContainer.set(REWARDED_PLAYERS_KEY, PersistentDataType.LIST.integerArrays(), playersOut);
        persistentDataContainer.set(REWARDED_COUNTS_KEY, PersistentDataType.LIST.integers(), countsOut);
        vaultState.update();
    }

    @EventHandler
    public void onVaultChangeState(final @NotNull VaultChangeStateEvent event) {
        final int delayTicks;
        if (((org.bukkit.block.data.type.Vault)event.getBlock().getBlockData()).isOminous())
            delayTicks = configuration.getDelay().getOminousVault();
        else
            delayTicks = configuration.getDelay().getVault();
        if (event.getNewState() == org.bukkit.block.data.type.Vault.State.UNLOCKING && delayTicks > 0) {
            final Player player = event.getPlayer();
            final UUID playerUUID;
            final org.bukkit.block.Vault vaultState;
            final PersistentDataContainer persistentDataContainer;
            if (player == null)
                return;
            playerUUID = player.getUniqueId();
            vaultState = (org.bukkit.block.Vault)event.getBlock().getState();
            persistentDataContainer = vaultState.getPersistentDataContainer();
            persistentDataContainer.set(
                UNLOCKING_PLAYER_KEY,
                PersistentDataType.INTEGER_ARRAY,
                uuidToIntArray(playerUUID));
            vaultState.update();
            reserveVaultCooldownForPlayer(event.getBlock(), playerUUID);
            return;
        }
        if (event.getCurrentState() != org.bukkit.block.data.type.Vault.State.EJECTING)
            return;
        if (delayTicks > 0) {
            final org.bukkit.block.Vault vaultState = (org.bukkit.block.Vault)event.getBlock().getState();
            final PersistentDataContainer persistentDataContainer = vaultState.getPersistentDataContainer();
            final int[] intArrayUUID = persistentDataContainer.get(UNLOCKING_PLAYER_KEY, PersistentDataType.INTEGER_ARRAY);
            if (intArrayUUID != null && intArrayUUID.length == 4) {
                final UUID player = intArrayToUuid(intArrayUUID);
                persistentDataContainer.remove(UNLOCKING_PLAYER_KEY);
                vaultState.update();
                startVaultCooldownForPlayer(event.getBlock(), player);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(final @NotNull ChunkLoadEvent event) {
        indexChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(final @NotNull ChunkUnloadEvent event) {
        final Chunk chunk = event.getChunk();
        vaultIndex.removeChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final @NotNull BlockPlaceEvent event) {
        final Block block = event.getBlockPlaced();
        if (block.getType() == Material.VAULT)
            vaultIndex.add(block.getWorld().getUID(), position(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final @NotNull BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (block.getType() == Material.VAULT)
            vaultIndex.remove(block.getWorld().getUID(), position(block));
    }

    /** Runs before the vault handles the key, so a vault the index missed (pasted by a
     *  world editor without a chunk reload) is picked up and its cooldowns cleared first. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(final @NotNull PlayerInteractEvent event) {
        final Block block;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        if ((block = event.getClickedBlock()) == null || block.getType() != Material.VAULT)
            return;
        vaultIndex.add(block.getWorld().getUID(), position(block));
        checkVaultCooldowns(block);
    }

    // MARK: JavaPlugin

    @Override
    public void onLoad() {
        super.onLoad();
        saveDefaultConfig();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        final Server server = getServer();
        new Metrics(this, BSTATS_PLUGIN_ID);
        configuration = new Configuration(this);
        server.getPluginManager().registerEvents(this, this);
        // Folia forbids reading other regions' chunks from here; its chunk load events fill
        // the index instead. On Paper, chunks loaded before enable (spawn) are scanned now.
        if (!isFolia()) {
            for (final var world : server.getWorlds())
                for (final var chunk : world.getLoadedChunks())
                    indexChunk(chunk);
            getLogger().info("Indexed " + vaultIndex.size() + " vaults in loaded chunks");
        }
        task =
            server.getGlobalRegionScheduler().runAtFixedRate(
                this,
                ($_0) -> {
                    for (final var player : server.getOnlinePlayers())
                        player.getScheduler().run(
                            this,
                            ($_1) -> {
                                checkVaultCooldownsNearPlayer(player);
                            },
                            null);
                },
                1,
                1);
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (task != null) {
            if (!task.isCancelled())
                task.cancel();
            task = null;
        }
    }
}
