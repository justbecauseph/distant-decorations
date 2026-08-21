package me.justbecause.distantdecorations.client.provider.camerapture;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.telemetry.TelemetryMetrics;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class CameraptureThumbnailScheduler {
    private static final CameraptureThumbnailScheduler INSTANCE = new CameraptureThumbnailScheduler();

    public static final int MAX_REQUESTS_PER_SECOND = 10;
    public static final int MAX_UPLOADS_PER_FRAME = 2;

    private final Map<UUID, Identifier> loadedTextures = new ConcurrentHashMap<>();
    private final Set<UUID> requestedPictures = ConcurrentHashMap.newKeySet();
    private final PriorityQueue<QueuedRequest> requestQueue = new PriorityQueue<>(Comparator.comparingDouble(QueuedRequest::priority).reversed());
    private final Object queueLock = new Object();

    private long lastRequestTime = 0;
    private int requestsThisSecond = 0;

    public record QueuedRequest(UUID pictureId, double priority) {}

    private CameraptureThumbnailScheduler() {}

    public static CameraptureThumbnailScheduler getInstance() {
        return INSTANCE;
    }

    @Nullable
    public Identifier getThumbnailTexture(UUID pictureId) {
        return loadedTextures.get(pictureId);
    }

    public void registerLoadedThumbnail(UUID pictureId, Identifier textureId) {
        loadedTextures.put(pictureId, textureId);
        requestedPictures.remove(pictureId);
        TelemetryMetrics.CAMERAPTURE_TEXTURE_UPLOADS.incrementAndGet();
    }

    public void requestThumbnail(UUID pictureId, double priority) {
        if (loadedTextures.containsKey(pictureId) || requestedPictures.contains(pictureId)) {
            return;
        }

        synchronized (queueLock) {
            requestedPictures.add(pictureId);
            requestQueue.add(new QueuedRequest(pictureId, priority));
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastRequestTime >= 1000) {
            lastRequestTime = now;
            requestsThisSecond = 0;
        }

        synchronized (queueLock) {
            while (!requestQueue.isEmpty() && requestsThisSecond < MAX_REQUESTS_PER_SECOND) {
                QueuedRequest req = requestQueue.poll();
                if (req != null && !loadedTextures.containsKey(req.pictureId())) {
                    sendThumbnailRequestPacket(req.pictureId());
                    requestsThisSecond++;
                    TelemetryMetrics.CAMERAPTURE_THUMBNAIL_REQUESTS.incrementAndGet();
                }
            }
        }
    }

    private void sendThumbnailRequestPacket(UUID pictureId) {
        // Hard Invariant: NEVER request FULL quality!
        try {
            Class<?> qualityEnum = Class.forName("me.chrr.camerapture.picture.PictureQuality");
            Object thumbnailQuality = Enum.valueOf((Class<Enum>) qualityEnum, "THUMBNAIL");

            Class<?> packetClass = Class.forName("me.chrr.camerapture.net.serverbound.RequestDownloadPacket");
            Constructor<?> constructor = packetClass.getConstructor(UUID.class, qualityEnum);
            Object packet = constructor.newInstance(pictureId, thumbnailQuality);

            Method sendMethod = ClientPlayNetworking.class.getMethod("send", Object.class);
            sendMethod.invoke(null, packet);
        } catch (Exception e) {
            DistantDecorations.LOGGER.debug("Could not send Camerapture thumbnail request packet for {}", pictureId, e);
        }
    }

    public void clear() {
        synchronized (queueLock) {
            requestQueue.clear();
            requestedPictures.clear();
        }
    }
}
