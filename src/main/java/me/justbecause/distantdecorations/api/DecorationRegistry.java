package me.justbecause.distantdecorations.api;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DecorationRegistry {
    private static final Map<Identifier, DecorationType<?>> TYPES = new ConcurrentHashMap<>();
    private static final Map<Identifier, DecorationProvider<?>> PROVIDERS = new ConcurrentHashMap<>();

    private DecorationRegistry() {}

    public static <T> DecorationType<T> registerType(DecorationType<T> type) {
        TYPES.put(type.id(), type);
        return type;
    }

    public static <T> void registerProvider(DecorationProvider<T> provider) {
        registerType(provider.type());
        PROVIDERS.put(provider.type().id(), provider);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> DecorationType<T> getType(Identifier id) {
        return (DecorationType<T>) TYPES.get(id);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> DecorationProvider<T> getProvider(Identifier id) {
        return (DecorationProvider<T>) PROVIDERS.get(id);
    }

    public static Collection<DecorationType<?>> getTypes() {
        return Collections.unmodifiableCollection(TYPES.values());
    }

    public static Collection<DecorationProvider<?>> getProviders() {
        return Collections.unmodifiableCollection(PROVIDERS.values());
    }

    @Nullable
    public static DecorationProvider<?> findProvider(BlockEntity blockEntity) {
        for (DecorationProvider<?> provider : PROVIDERS.values()) {
            if (provider.matches(blockEntity)) {
                return provider;
            }
        }
        return null;
    }
}
