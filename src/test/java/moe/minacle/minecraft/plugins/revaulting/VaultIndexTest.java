package moe.minacle.minecraft.plugins.revaulting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class VaultIndexTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static final UUID OTHER_WORLD = UUID.randomUUID();

    private static VaultIndex.Position at(final int x, final int y, final int z) {
        return new VaultIndex.Position(x, y, z);
    }

    @Test
    void nearFindsExactlyTheVaultsInsideTheCube() {
        final var index = new VaultIndex();
        index.replaceChunk(WORLD, 0, 0, List.of(at(5, -20, 5), at(9, -20, 5), at(10, -20, 5), at(5, -24, 5)));
        final var near = index.near(WORLD, 6, -20, 6, 3);
        assertEquals(1, near.size());
        assertEquals(List.of(at(5, -20, 5), at(9, -20, 5)),
                near.get(VaultIndex.chunkKey(0, 0)).stream()
                        .sorted(java.util.Comparator.comparingInt(VaultIndex.Position::x)).toList(),
                "3 blocks out is in; x 10 and y -24 are 4 blocks out on one axis");
    }

    @Test
    void nearGroupsByChunkAcrossBordersAndNegativeCoordinates() {
        final var index = new VaultIndex();
        index.add(WORLD, at(-1, 10, -1));
        index.add(WORLD, at(0, 10, 0));
        index.add(WORLD, at(-1, 10, 1));
        final var near = index.near(WORLD, 0, 10, 0, 3);
        assertEquals(3, near.size(), "a player on a chunk corner sees vaults in all touching chunks");
        assertEquals(List.of(at(-1, 10, -1)), near.get(VaultIndex.chunkKey(-1, -1)));
        assertEquals(List.of(at(0, 10, 0)), near.get(VaultIndex.chunkKey(0, 0)));
        assertEquals(List.of(at(-1, 10, 1)), near.get(VaultIndex.chunkKey(-1, 0)));
    }

    @Test
    void worldsAreSeparate() {
        final var index = new VaultIndex();
        index.add(WORLD, at(0, 0, 0));
        assertTrue(index.near(OTHER_WORLD, 0, 0, 0, 3).isEmpty());
    }

    @Test
    void replaceRemoveAndUnloadForgetVaults() {
        final var index = new VaultIndex();
        index.replaceChunk(WORLD, 2, 3, List.of(at(33, 0, 49), at(34, 0, 49)));
        assertEquals(2, index.size());

        index.remove(WORLD, at(33, 0, 49));
        assertEquals(1, index.size());

        index.replaceChunk(WORLD, 2, 3, List.of());
        assertEquals(0, index.size(), "a reload with no vaults forgets the chunk");

        index.add(WORLD, at(33, 0, 49));
        index.removeChunk(WORLD, 2, 3);
        assertTrue(index.near(WORLD, 33, 0, 49, 3).isEmpty(), "unloading forgets the chunk");
    }

    @Test
    void emptyIndexAnswersEmpty() {
        assertTrue(new VaultIndex().near(WORLD, 0, 0, 0, 3).isEmpty());
    }
}
