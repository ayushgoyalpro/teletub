package com.ayush.teletub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StreamResponse {

    private List<Stream> streams;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Stream {
        private String url;
        private String title;
        private String name;
        private BehaviorHints behaviorHints;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BehaviorHints {
        private Boolean notWebReady;
        private String proxyHeaders;
    }
}
