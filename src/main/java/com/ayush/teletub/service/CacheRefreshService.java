package com.ayush.teletub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheRefreshService {

    private final CacheManager cacheManager;
    private final ChannelService channelService;
    private final ScheduleService scheduleService;

    @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 0)
    public void refreshCaches() {
        refreshChannels();
        refreshSchedule();
    }

    private void refreshChannels() {
        try {
            var cache = cacheManager.getCache("channels");
            if (cache != null) cache.clear();
            channelService.getChannels();
            log.info("Channels cache refreshed");
        } catch (Exception e) {
            log.error("Failed to refresh channels cache", e);
        }
    }

    private void refreshSchedule() {
        try {
            var cache = cacheManager.getCache("schedule");
            if (cache != null) cache.clear();
            scheduleService.getSchedule();
            log.info("Schedule cache refreshed");
        } catch (Exception e) {
            log.error("Failed to refresh schedule cache", e);
        }
    }
}
