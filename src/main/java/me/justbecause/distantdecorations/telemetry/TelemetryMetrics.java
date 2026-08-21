package me.justbecause.distantdecorations.telemetry;

import java.util.concurrent.atomic.AtomicLong;

public final class TelemetryMetrics {
    // --- Server Metrics ---
    public static final AtomicLong SERVER_INDEXED_DECORATIONS = new AtomicLong();
    public static final AtomicLong SERVER_REGIONS = new AtomicLong();
    public static final AtomicLong SERVER_ADDS = new AtomicLong();
    public static final AtomicLong SERVER_UPDATES = new AtomicLong();
    public static final AtomicLong SERVER_REMOVES = new AtomicLong();
    public static final AtomicLong SERVER_CHUNKY_SCANS = new AtomicLong();
    public static final AtomicLong SERVER_CHUNK_RECON_SCANS = new AtomicLong();
    public static final AtomicLong SERVER_SNAPSHOTS_SENT = new AtomicLong();
    public static final AtomicLong SERVER_DELTAS_SENT = new AtomicLong();
    public static final AtomicLong SERVER_METADATA_BYTES_SENT = new AtomicLong();

    // --- Client Frame Metrics (reset per frame) ---
    public static long clientCellsChecked;
    public static long clientCellsFrustumRejected;
    public static long clientObjectsChecked;
    public static long clientObjectsFrustumRejected;
    public static long clientSubpixelRejected;
    public static long clientBudgetRejected;
    public static long clientLiveSuppressions;
    public static long clientRenderedDecorations;

    // --- Client Cumulative Metrics ---
    public static final AtomicLong CLIENT_SYNCED_DECORATIONS = new AtomicLong();
    public static final AtomicLong CLIENT_SYNCED_REGIONS = new AtomicLong();
    public static final AtomicLong CLIENT_TOTAL_RENDERED = new AtomicLong();

    // --- Provider Metrics ---
    public static final AtomicLong FAST_PAINTINGS_FAR_RENDERS = new AtomicLong();
    public static final AtomicLong FAST_PAINTINGS_SIMPLIFIED_RENDERS = new AtomicLong();
    public static final AtomicLong CAMERAPTURE_THUMBNAIL_RENDERS = new AtomicLong();
    public static final AtomicLong CAMERAPTURE_THUMBNAIL_REQUESTS = new AtomicLong();
    public static final AtomicLong CAMERAPTURE_CACHE_HITS = new AtomicLong();
    public static final AtomicLong CAMERAPTURE_TEXTURE_UPLOADS = new AtomicLong();
    public static final AtomicLong CAMERAPTURE_UPLOAD_BYTES = new AtomicLong();

    public static void beginClientFrame() {
        clientCellsChecked = 0;
        clientCellsFrustumRejected = 0;
        clientObjectsChecked = 0;
        clientObjectsFrustumRejected = 0;
        clientSubpixelRejected = 0;
        clientBudgetRejected = 0;
        clientLiveSuppressions = 0;
        clientRenderedDecorations = 0;
    }

    public static void resetAll() {
        SERVER_INDEXED_DECORATIONS.set(0);
        SERVER_REGIONS.set(0);
        SERVER_ADDS.set(0);
        SERVER_UPDATES.set(0);
        SERVER_REMOVES.set(0);
        SERVER_CHUNKY_SCANS.set(0);
        SERVER_CHUNK_RECON_SCANS.set(0);
        SERVER_SNAPSHOTS_SENT.set(0);
        SERVER_DELTAS_SENT.set(0);
        SERVER_METADATA_BYTES_SENT.set(0);

        CLIENT_SYNCED_DECORATIONS.set(0);
        CLIENT_SYNCED_REGIONS.set(0);
        CLIENT_TOTAL_RENDERED.set(0);

        FAST_PAINTINGS_FAR_RENDERS.set(0);
        FAST_PAINTINGS_SIMPLIFIED_RENDERS.set(0);
        CAMERAPTURE_THUMBNAIL_RENDERS.set(0);
        CAMERAPTURE_THUMBNAIL_REQUESTS.set(0);
        CAMERAPTURE_CACHE_HITS.set(0);
        CAMERAPTURE_TEXTURE_UPLOADS.set(0);
        CAMERAPTURE_UPLOAD_BYTES.set(0);

        beginClientFrame();
    }
}
