package com.ayush.teletub.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamResolverService {

    private static final String DLHD_BASE = "https://dlhd.pk";
    private static final int RESOLVE_TIMEOUT_MS = 30_000;

    private final PlaywrightService playwright;

    /**
     * Resolution strategy:
     * 1. Open watch.php?id=N (dlhd's own wrapper page)
     * 2. Extract the iframe src from that page
     * 3. Navigate directly to the iframe URL — this triggers the player JS
     * 4. Network interceptor captures the first .m3u8 request that fires
     */
    public String resolveM3u8(int watchId) throws Exception {
        String watchUrl = DLHD_BASE + "/watch.php?id=" + watchId;
        log.info("Resolving m3u8 for watch id={} via {}", watchId, watchUrl);

        return playwright.withContext(ctx -> {
            CompletableFuture<String> m3u8Future = new CompletableFuture<>();

            try (Page page = ctx.newPage()) {
                // Register the interceptor BEFORE any navigation
                page.onRequest(req -> {
                    String url = req.url();
                    if (url.contains(".m3u8") && !m3u8Future.isDone()) {
                        log.info("Captured m3u8 from request: {}", url);
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

                // Step 1: load the dlhd wrapper page to get the iframe src
                page.navigate(watchUrl, new Page.NavigateOptions()
                        .setTimeout(RESOLVE_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                // The m3u8 might already fire if the wrapper page auto-loads the player
                if (m3u8Future.isDone()) {
                    return m3u8Future.get();
                }

                // Step 2: find the iframe and navigate into it
                String iframeSrc = extractIframeSrc(page);
                if (iframeSrc != null) {
                    log.debug("Navigating into iframe: {}", iframeSrc);
                    page.navigate(iframeSrc, new Page.NavigateOptions()
                            .setTimeout(RESOLVE_TIMEOUT_MS)
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                }

                // Step 3: wait up to timeout for the m3u8 to appear
                String m3u8 = m3u8Future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info("Resolved watch id={}: {}", watchId, m3u8);
                return m3u8;
            }
        });
    }

    private String extractIframeSrc(Page page) {
        try {
            // Short wait for iframe to appear in DOM
            page.waitForSelector("iframe[src]",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));
            ElementHandle iframe = page.querySelector("iframe[src]");
            if (iframe != null) {
                String src = iframe.getAttribute("src");
                if (src != null && !src.isBlank() && !src.equals("about:blank")) {
                    // Make absolute if relative
                    return src.startsWith("http") ? src : DLHD_BASE + src;
                }
            }

            // Fallback: check child frames already loaded
            List<Frame> frames = page.frames();
            for (Frame frame : frames) {
                if (frame == page.mainFrame()) continue;
                String url = frame.url();
                if (url != null && !url.isBlank() && !url.equals("about:blank")) {
                    return url;
                }
            }
        } catch (Exception e) {
            log.debug("iframe extraction: {}", e.getMessage());
        }
        return null;
    }
}
