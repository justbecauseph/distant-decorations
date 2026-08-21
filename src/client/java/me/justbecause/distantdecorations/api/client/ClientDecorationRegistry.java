package me.justbecause.distantdecorations.api.client;

import me.justbecause.distantdecorations.api.DecorationRegistry;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecorationRegistry {
    private static final Map<Identifier, DecorationClientRenderer<?>> RENDERERS = new ConcurrentHashMap<>();

    private ClientDecorationRegistry() {}

    public static <T> void registerRenderer(DecorationClientRenderer<T> renderer) {
        DecorationRegistry.registerType(renderer.type());
        RENDERERS.put(renderer.type().id(), renderer);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> DecorationClientRenderer<T> getRenderer(Identifier id) {
        return (DecorationClientRenderer<T>) RENDERERS.get(id);
    }

    public static Collection<DecorationClientRenderer<?>> getRenderers() {
        return Collections.unmodifiableCollection(RENDERERS.values());
    }
}
