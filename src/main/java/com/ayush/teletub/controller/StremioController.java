package com.ayush.teletub.controller;

import com.ayush.teletub.model.*;
import com.ayush.teletub.service.ChannelService;
import com.ayush.teletub.service.ScheduleService;
import com.ayush.teletub.service.PlayerUrlService;
import com.ayush.teletub.service.StreamResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StremioController {

    private static final String ADDON_ID      = "community.dlhd.live";
    private static final String ADDON_VERSION  = "1.0.0";
    private static final String TYPE_TV        = "tv";
    private static final String CAT_247        = "dlhd_247";
    private static final String CAT_SCHEDULE   = "dlhd_schedule";
    private static final String PREFIX_247     = "dlhd_";
    private static final String PREFIX_SCHED   = "dlhd_sched_";

    private final ChannelService channelService;
    private final ScheduleService scheduleService;
    private final StreamResolverService streamResolver;
    private final PlayerUrlService playerUrlService;

    // ── Manifest ─────────────────────────────────────────────────────────────

    @GetMapping("/manifest.json")
    public ResponseEntity<StremioManifest> manifest() {
        StremioManifest manifest = StremioManifest.builder()
                .id(ADDON_ID)
                .version(ADDON_VERSION)
                .name("DLHD Live TV")
                .description("Live sports and 24/7 channels from dlhd.pk")
                .types(List.of(TYPE_TV))
                .catalogs(List.of(
                        StremioManifest.CatalogEntry.builder()
                                .type(TYPE_TV).id(CAT_247).name("24/7 Channels").build(),
                        StremioManifest.CatalogEntry.builder()
                                .type(TYPE_TV).id(CAT_SCHEDULE).name("Sports Schedule").build()
                ))
                .resources(List.of("catalog", "meta", "stream"))
                .idPrefixes(List.of(PREFIX_247, PREFIX_SCHED))
                .build();

        return corsOk(manifest);
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    @GetMapping("/catalog/{type}/{id}.json")
    public ResponseEntity<StremioMeta.CatalogResponse> catalog(
            @PathVariable String type,
            @PathVariable String id) throws Exception {

        if (!TYPE_TV.equals(type)) {
            return corsOk(StremioMeta.CatalogResponse.builder().metas(List.of()).build());
        }

        return switch (id) {
            case CAT_247      -> corsOk(buildChannelCatalog());
            case CAT_SCHEDULE -> corsOk(buildScheduleCatalog());
            default           -> corsOk(StremioMeta.CatalogResponse.builder().metas(List.of()).build());
        };
    }

    private StremioMeta.CatalogResponse buildChannelCatalog() throws Exception {
        List<StremioMeta> metas = channelService.getChannels().stream()
                .map(ch -> StremioMeta.builder()
                        .id(ch.getId())
                        .type(TYPE_TV)
                        .name(ch.getName())
                        .poster(ch.getLogo())
                        .logo(ch.getLogo())
                        .build())
                .toList();
        return StremioMeta.CatalogResponse.builder().metas(metas).build();
    }

    private StremioMeta.CatalogResponse buildScheduleCatalog() throws Exception {
        List<StremioMeta> metas = scheduleService.getSchedule().stream()
                .map(ev -> {
                    String channels = ev.getChannels().stream()
                            .map(ScheduleEvent.ChannelRef::getName)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    return StremioMeta.builder()
                            .id(ev.getId())
                            .type(TYPE_TV)
                            .name(ev.getTitle())
                            .description(ev.getTimeUtc() + " UTC  •  " + ev.getDay() + "\n" + channels)
                            .genres(List.of(ev.getCategory()))
                            .build();
                })
                .toList();
        return StremioMeta.CatalogResponse.builder().metas(metas).build();
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    @GetMapping("/meta/{type}/{id}.json")
    public ResponseEntity<StremioMeta.MetaResponse> meta(
            @PathVariable String type,
            @PathVariable String id) throws Exception {

        if (!TYPE_TV.equals(type)) return ResponseEntity.notFound().build();

        if (id.startsWith(PREFIX_SCHED)) {
            ScheduleEvent ev = scheduleService.findById(id);
            if (ev == null) return ResponseEntity.notFound().build();

            String channels = ev.getChannels().stream()
                    .map(ScheduleEvent.ChannelRef::getName)
                    .reduce((a, b) -> a + ", " + b).orElse("");

            return corsOk(StremioMeta.MetaResponse.builder()
                    .meta(StremioMeta.builder()
                            .id(ev.getId()).type(TYPE_TV).name(ev.getTitle())
                            .description(ev.getTimeUtc() + " UTC  •  " + ev.getDay() + "\n" + channels)
                            .genres(List.of(ev.getCategory()))
                            .build())
                    .build());
        }

        if (id.startsWith(PREFIX_247)) {
            Channel ch = channelService.findById(id);
            if (ch == null) return ResponseEntity.notFound().build();

            return corsOk(StremioMeta.MetaResponse.builder()
                    .meta(StremioMeta.builder()
                            .id(ch.getId()).type(TYPE_TV).name(ch.getName())
                            .poster(ch.getLogo()).logo(ch.getLogo())
                            .description("Live 24/7 channel from dlhd.pk")
                            .build())
                    .build());
        }

        return ResponseEntity.notFound().build();
    }

    // ── Stream ────────────────────────────────────────────────────────────────

    @GetMapping("/stream/{type}/{id}.json")
    public ResponseEntity<StreamResponse> stream(
            @PathVariable String type,
            @PathVariable String id) throws Exception {

        if (!TYPE_TV.equals(type)) {
            return corsOk(StreamResponse.builder().streams(List.of()).build());
        }

        if (id.startsWith(PREFIX_SCHED)) {
            return corsOk(resolveScheduleStreams(id));
        }

        if (id.startsWith(PREFIX_247)) {
            return corsOk(resolveChannelStream(id));
        }

        return corsOk(StreamResponse.builder().streams(List.of()).build());
    }

    private StreamResponse resolveChannelStream(String id) throws Exception {
        Channel ch = channelService.findById(id);
        if (ch == null) return StreamResponse.builder().streams(List.of()).build();

        try {
            String m3u8 = streamResolver.resolveM3u8(ch.getWatchId());
            return StreamResponse.builder()
                    .streams(List.of(StreamResponse.Stream.builder()
                            .url(m3u8).title(ch.getName()).name("DLHD").build()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to resolve stream for {}: {}", id, e.getMessage());
            return StreamResponse.builder().streams(List.of()).build();
        }
    }

    private StreamResponse resolveScheduleStreams(String id) throws Exception {
        ScheduleEvent ev = scheduleService.findById(id);
        if (ev == null) return StreamResponse.builder().streams(List.of()).build();

        // Resolve all channels for this event in parallel
        List<CompletableFuture<StreamResponse.Stream>> futures = ev.getChannels().stream()
                .map(ref -> CompletableFuture.supplyAsync(() -> {
                    try {
                        String m3u8 = streamResolver.resolveM3u8(ref.getWatchId());
                        return StreamResponse.Stream.builder()
                                .url(m3u8)
                                .title(ref.getName())
                                .name("DLHD")
                                .build();
                    } catch (Exception e) {
                        log.warn("Failed to resolve stream for channel {}: {}", ref.getName(), e.getMessage());
                        return null;
                    }
                }))
                .toList();

        List<StreamResponse.Stream> streams = new ArrayList<>();
        for (CompletableFuture<StreamResponse.Stream> f : futures) {
            StreamResponse.Stream s = f.get();
            if (s != null) streams.add(s);
        }

        return StreamResponse.builder().streams(streams).build();
    }

    // ── Player URL resolver (bypasses dlhd wrapper, returns daddy.php URL) ──

    @GetMapping("/data/player-url/{watchId}")
    public ResponseEntity<java.util.Map<String, String>> playerUrl(@PathVariable int watchId) {
        String url = playerUrlService.resolvePlayerUrl(watchId);
        return corsOk(java.util.Map.of("url", url));
    }

    // ── Raw data endpoints (for the SPA — no stream resolution) ─────────────

    @GetMapping("/data/channels")
    public ResponseEntity<List<Channel>> dataChannels() throws Exception {
        return corsOk(channelService.getChannels());
    }

    @GetMapping("/data/schedule")
    public ResponseEntity<List<ScheduleEvent>> dataSchedule() throws Exception {
        return corsOk(scheduleService.getSchedule());
    }

    private <T> ResponseEntity<T> corsOk(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(body);
    }
}
