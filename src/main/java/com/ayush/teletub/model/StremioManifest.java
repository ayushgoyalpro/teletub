package com.ayush.teletub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StremioManifest {

    private String id;
    private String version;
    private String name;
    private String description;
    private String logo;
    private List<String> types;
    private List<CatalogEntry> catalogs;
    private List<String> resources;
    private List<String> idPrefixes;
    private BehaviorHints behaviorHints;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CatalogEntry {
        private String type;
        private String id;
        private String name;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BehaviorHints {
        private Boolean configurable;
        private Boolean adult;
    }
}
