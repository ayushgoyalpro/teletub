package com.ayush.teletub.controller;

import com.ayush.teletub.service.StreamResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StreamProxyController {

    private static final long CACHE_TTL_MS = 20 * 60 * 1000L;

    private record CachedM3u8(String url, long resolvedAt) {}

    private final StreamResolverService streamResolver;
    private final ConcurrentHashMap<Integer, CachedM3u8> m3u8Cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    @GetMapping("/stream/manifest/{watchId}")
    public ResponseEntity<String> manifest(@PathVariable int watchId) throws Exception {
        String m3u8Url = resolveWithCache(watchId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(m3u8Url);
    }

    private String resolveWithCache(int watchId) throws Exception {
        CachedM3u8 cached = m3u8Cache.get(watchId);
        if (cached != null && System.currentTimeMillis() - cached.resolvedAt() < CACHE_TTL_MS) {
            return cached.url();
        }

        // Deduplicate: concurrent requests for the same watchId share one Playwright call
        CompletableFuture<String> future = inFlight.computeIfAbsent(watchId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("Resolving m3u8 for watchId={}", id);
                        String url = streamResolver.resolveM3u8(id);
                        m3u8Cache.put(id, new CachedM3u8(url, System.currentTimeMillis()));
                        return url;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        inFlight.remove(id);
                    }
                })
        );

        return future.get();
    }
}
