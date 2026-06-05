package com.itqianchen.agentdesign.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RagSourcesJsonCodec {

    private static final Logger log = LoggerFactory.getLogger(RagSourcesJsonCodec.class);
    private static final TypeReference<List<RagSourceResponse>> SOURCES_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public RagSourcesJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(List<RagSourceResponse> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources.stream()
                    .map(source -> source.withContent(null))
                    .toList());
        } catch (JsonProcessingException ex) {
            log.warn("rag_sources_encode_failed", ex);
            return null;
        }
    }

    public List<RagSourceResponse> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, SOURCES_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("rag_sources_decode_failed", ex);
            return List.of();
        }
    }
}
