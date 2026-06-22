package com.ayush.teletub.service;

import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Manages a small pool of Playwright browser contexts.
 * Browser startup is expensive (~2s), so we reuse contexts across requests.
 */
@Slf4j
@Service
public class PlaywrightService {

    private static final int POOL_SIZE = 3;

    private Playwright playwright;
    private Browser browser;
    private final BlockingQueue<BrowserContext> contextPool = new ArrayBlockingQueue<>(POOL_SIZE);

    @PostConstruct
    public void init() {
        log.info("Initializing Playwright browser pool (size={})", POOL_SIZE);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(java.util.List.of(
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-gpu"
                )));

        for (int i = 0; i < POOL_SIZE; i++) {
            contextPool.add(createContext());
        }
        log.info("Playwright browser pool ready");
    }

    private BrowserContext createContext() {
        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .setIgnoreHTTPSErrors(true));
    }

    /**
     * Borrow a context from the pool, run the task, return it.
     * If the context is stale after an error, replace it with a fresh one.
     */
    public <T> T withContext(ContextTask<T> task) throws Exception {
        BrowserContext ctx = contextPool.take();
        try {
            return task.run(ctx);
        } catch (Exception e) {
            // Replace stale context
            try { ctx.close(); } catch (Exception ignored) {}
            ctx = createContext();
            throw e;
        } finally {
            contextPool.offer(ctx);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Playwright");
        contextPool.forEach(ctx -> { try { ctx.close(); } catch (Exception ignored) {} });
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @FunctionalInterface
    public interface ContextTask<T> {
        T run(BrowserContext context) throws Exception;
    }
}
