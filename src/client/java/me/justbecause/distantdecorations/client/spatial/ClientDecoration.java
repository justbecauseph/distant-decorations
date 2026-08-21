package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.api.client.ClientDecorationRegistry;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public final class ClientDecoration {
    private final DecorationRecord record;
    private final @Nullable Object decodedPayload;
    private final @Nullable DecorationClientRenderer<Object> renderer;

    @SuppressWarnings("unchecked")
    public ClientDecoration(DecorationRecord record) {
        this.record = record;
        DecorationClientRenderer<?> r = ClientDecorationRegistry.getRenderer(record.id().type());
        this.renderer = (DecorationClientRenderer<Object>) r;
        if (r != null) {
            DecorationType<?> type = r.type();
            this.decodedPayload = type.fromBytes(record.payload());
        } else {
            this.decodedPayload = null;
        }
    }

    public DecorationRecord record() {
        return record;
    }

    public DecorationId id() {
        return record.id();
    }

    public AABB bounds() {
        return record.bounds();
    }

    public long revision() {
        return record.revision();
    }

    @Nullable
    public Object decodedPayload() {
        return decodedPayload;
    }

    @Nullable
    public DecorationClientRenderer<Object> renderer() {
        return renderer;
    }

    public boolean isValid() {
        return renderer != null && decodedPayload != null;
    }
}
