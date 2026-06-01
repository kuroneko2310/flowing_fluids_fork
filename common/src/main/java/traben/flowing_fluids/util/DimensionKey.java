package traben.flowing_fluids.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dimension identifier wrapper that gracefully falls back to the accessor instance identity
 * when we cannot obtain a {@link ResourceKey}. This guarantees that per-dimension caches
 * never bleed into one another even for temporary level implementations (e.g., world-gen regions).
 */
public final class DimensionKey {

    private static final ConcurrentHashMap<ResourceKey<Level>, DimensionKey> INTERNED_DIMENSIONS = new ConcurrentHashMap<>();

    private final Object key;
    private final boolean identity;

    private DimensionKey(Object key, boolean identity) {
        this.key = key;
        this.identity = identity;
    }

    public static DimensionKey of(Level level) {
        return of(level.dimension());
    }

    public static DimensionKey of(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return new DimensionKey(null, false);
        }
        return INTERNED_DIMENSIONS.computeIfAbsent(dimension, key -> new DimensionKey(key, false));
    }

    public static DimensionKey ofIdentity(Object token) {
        return new DimensionKey(token, true);
    }

    public static DimensionKey of(LevelAccessor accessor) {
        if (accessor instanceof Level level) {
            return of(level);
        }
        // Fallback: rely on object identity so each accessor instance is isolated.
        return new DimensionKey(accessor, true);
    }

    public Object raw() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimensionKey that)) return false;
        if (identity != that.identity) return false;
        if (identity) {
            return key == that.key;
        }
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return identity ? System.identityHashCode(key) : Objects.hashCode(key);
    }

    @Override
    public String toString() {
        if (!identity) {
            return "DimensionKey[" + key + "]";
        }
        return "DimensionKey[identity:" + key.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(key)) + "]";
    }
}
