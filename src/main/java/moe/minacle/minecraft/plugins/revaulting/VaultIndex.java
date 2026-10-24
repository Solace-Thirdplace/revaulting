package moe.minacle.minecraft.plugins.revaulting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

/**
 * Where the vaults are, per world and chunk, so the per-tick proximity check looks up a
 * handful of known positions instead of reading every block in a cube around each player.
 * Filled from each chunk's block entities when it loads, kept current by block place and
 * break, emptied when the chunk unloads. Thread-safe: on Folia, chunk events and player
 * checks run on different region threads.
 */
final class VaultIndex {

    record Position(int x, int y, int z) {}

    static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private final @NotNull Map<@NotNull UUID, @NotNull Map<@NotNull Long, @NotNull Set<@NotNull Position>>> byWorld = new ConcurrentHashMap<>();

    private @NotNull Map<@NotNull Long, @NotNull Set<@NotNull Position>> world(final @NotNull UUID world) {
        return byWorld.computeIfAbsent(world, $ -> new ConcurrentHashMap<>());
    }

    /** Replaces what is known about one chunk; an empty collection forgets the chunk. */
    void replaceChunk(final @NotNull UUID world, final int chunkX, final int chunkZ, final @NotNull Collection<@NotNull Position> vaults) {
        final long key = chunkKey(chunkX, chunkZ);
        if (vaults.isEmpty()) {
            removeChunk(world, chunkX, chunkZ);
            return;
        }
        final Set<Position> set = ConcurrentHashMap.newKeySet(vaults.size());
        set.addAll(vaults);
        world(world).put(key, set);
    }

    void removeChunk(final @NotNull UUID world, final int chunkX, final int chunkZ) {
        final Map<Long, Set<Position>> chunks = byWorld.get(world);
        if (chunks != null)
            chunks.remove(chunkKey(chunkX, chunkZ));
    }

    void add(final @NotNull UUID world, final @NotNull Position position) {
        world(world)
            .computeIfAbsent(chunkKey(position.x() >> 4, position.z() >> 4), $ -> ConcurrentHashMap.newKeySet())
            .add(position);
    }

    void remove(final @NotNull UUID world, final @NotNull Position position) {
        final Map<Long, Set<Position>> chunks = byWorld.get(world);
        final long key = chunkKey(position.x() >> 4, position.z() >> 4);
        final Set<Position> set;
        if (chunks == null || (set = chunks.get(key)) == null)
            return;
        set.remove(position);
        if (set.isEmpty())
            chunks.remove(key, set);
    }

    /** Known vaults within {@code radius} blocks of (x, y, z) on every axis, grouped by chunk key. */
    @NotNull Map<@NotNull Long, @NotNull List<@NotNull Position>> near(final @NotNull UUID world, final int x, final int y, final int z, final int radius) {
        final Map<Long, Set<Position>> chunks = byWorld.get(world);
        if (chunks == null || chunks.isEmpty())
            return Map.of();
        Map<Long, List<Position>> found = null;
        for (int chunkX = (x - radius) >> 4; chunkX <= (x + radius) >> 4; chunkX++) {
            for (int chunkZ = (z - radius) >> 4; chunkZ <= (z + radius) >> 4; chunkZ++) {
                final long key = chunkKey(chunkX, chunkZ);
                final Set<Position> set = chunks.get(key);
                if (set == null)
                    continue;
                for (final var position : set) {
                    if (Math.abs(position.x() - x) > radius || Math.abs(position.y() - y) > radius || Math.abs(position.z() - z) > radius)
                        continue;
                    if (found == null)
                        found = new HashMap<>();
                    found.computeIfAbsent(key, $ -> new ArrayList<>()).add(position);
                }
            }
        }
        return found == null ? Map.of() : found;
    }

    int size() {
        int size = 0;
        for (final var chunks : byWorld.values())
            for (final var set : chunks.values())
                size += set.size();
        return size;
    }
}
