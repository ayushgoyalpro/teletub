package com.ayush.teletub.controller;

import com.ayush.teletub.model.Channel;
import com.ayush.teletub.model.ScheduleEvent;
import com.ayush.teletub.service.ChannelService;
import com.ayush.teletub.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AppController {

    private final ChannelService channelService;
    private final ScheduleService scheduleService;

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
