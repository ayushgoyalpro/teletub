package com.ayush.teletub.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamResolverService {

    private static final String DLHD_BASE = "https://dlhd.pk";
    private static final int RESOLVE_TIMEOUT_MS = 40_000;

    private static final String[] PLAYER_PATHS = {
            "/stream/stream-%d.php",
            "/cast/stream-%d.php",
            "/watch/stream-%d.php",
    };

    // Served locally as the parent page so the stream loads inside an iframe.
    // The stream page sees WRAPPER_ORIGIN as Referer and window !== window.top.
    private static final String WRAPPER_ORIGIN = "https://www.iframetester.com";
    private static final String WRAPPER_URL    = WRAPPER_ORIGIN + "/?url=";

    private final PlaywrightService playwright;

    public String resolveM3u8(int watchId) throws Exception {
        for (String pathTemplate : PLAYER_PATHS) {
            String playerUrl = DLHD_BASE + String.format(pathTemplate, watchId);
            try {
                return tryResolve(playerUrl);
            } catch (Exception e) {
                log.warn("Failed on {}: {}", playerUrl, e.getMessage());
            }
        }
        throw new RuntimeException("Could not resolve m3u8 for watchId=" + watchId);
    }

    private String tryResolve(String playerUrl) throws Exception {
        log.info("Resolving m3u8 via iframe wrapper: {}", playerUrl);
        return playwright.withContext(ctx -> {
            try (Page page = ctx.newPage()) {
                page.route(WRAPPER_URL + "**", route -> route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/html")
                        .setBody("<!DOCTYPE html><html><body style='margin:0'>" +
                                "<iframe src=\"" + playerUrl + "\" width='100%' height='100%' " +
                                "style='border:none' allowfullscreen allow='autoplay'></iframe>" +
                                "</body></html>")));

                Request m3u8 = page.waitForRequest(
                        req -> req.url().contains(".m3u8"),
                        new Page.WaitForRequestOptions().setTimeout(RESOLVE_TIMEOUT_MS),
                        () -> page.navigate(WRAPPER_URL + playerUrl, new Page.NavigateOptions()
                                .setTimeout(RESOLVE_TIMEOUT_MS)
                                .setWaitUntil(WaitUntilState.COMMIT)));

                log.info("Captured m3u8: {}", m3u8.url());
                return m3u8.url();
            }
        });
    }
}