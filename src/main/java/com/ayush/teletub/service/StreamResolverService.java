package com.ayush.teletub.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamResolverService {

    private static final String DLHD_BASE = "https://dlhd.pk";
    private static final int RESOLVE_TIMEOUT_MS = 25_000;

    // Player path prefixes, tried in order. watch.php?id=N always maps to stream-N.php.
    private static final String[] PLAYER_PATHS = {
            "/stream/stream-%d.php",
            "/cast/stream-%d.php",
            "/watch/stream-%d.php",
    };

    private final PlaywrightService playwright;

    /**
     * Navigates directly to the stream player page for the given watchId,
     * intercepts the first .m3u8 network request, and returns the URL.
     *
     * watch.php?id=N and stream-N.php always share the same numeric ID,
     * so we skip watch.php entirely.
     */
    public String resolveM3u8(int watchId) throws Exception {
        for (String pathTemplate : PLAYER_PATHS) {
            String playerUrl = DLHD_BASE + String.format(pathTemplate, watchId);
            try {
                String m3u8 = tryResolve(playerUrl, watchId);
                if (m3u8 != null) return m3u8;
            } catch (TimeoutException e) {
                log.warn("Timeout on {}, trying next player", playerUrl);
            } catch (Exception e) {
                log.warn("Error on {}: {}", playerUrl, e.getMessage());
            }
        }
        throw new RuntimeException("Could not resolve m3u8 for watchId=" + watchId + " after trying all players");
    }

    private String tryResolve(String playerUrl, int watchId) throws Exception {
        log.info("Resolving m3u8: {}", playerUrl);
        return playwright.withContext(ctx -> {
            CompletableFuture<String> m3u8Future = new CompletableFuture<>();

            try (Page page = ctx.newPage()) {
                page.onRequest(req -> {
                    String url = req.url();
                    if (url.contains(".m3u8") && !m3u8Future.isDone()) {
                        log.info("Captured m3u8: {}", url);
                        m3u8Future.complete(url);
                    }
                });
                page.onResponse(resp -> {
                    String url = resp.url();
                    if (url.contains(".m3u8") && !m3u8Future.isDone()) {
                        log.info("Captured m3u8 from response: {}", url);
                        m3u8Future.complete(url);
                    }
                });

                page.navigate(playerUrl, new Page.NavigateOptions()
                        .setTimeout(RESOLVE_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                return m3u8Future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        });
    }
}
