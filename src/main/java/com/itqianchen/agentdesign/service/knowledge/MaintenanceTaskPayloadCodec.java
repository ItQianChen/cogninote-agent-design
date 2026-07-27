package com.itqianchen.agentdesign.service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.vo.knowledge.MaintenanceTaskPayloadV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 维护任务 payload 的严格版本边界和稳定幂等键生成器。 */
@Component
public class MaintenanceTaskPayloadCodec {

    public static final int PAYLOAD_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final ObjectReader strictReader;

    public MaintenanceTaskPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.readerFor(MaintenanceTaskPayloadV1.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String encode(MaintenanceTaskPayloadV1 payload) {
        validate(payload);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode maintenance task payload", ex);
        }
    }

    public MaintenanceTaskPayloadV1 decode(int payloadVersion, String payloadJson) {
        if (payloadVersion != PAYLOAD_VERSION) {
            throw new UnsupportedMaintenancePayloadException("不支持的维护任务 payload 版本：" + payloadVersion);
        }
        try {
            MaintenanceTaskPayloadV1 payload = strictReader.readValue(payloadJson);
            validate(payload);
            return payload;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new UnsupportedMaintenancePayloadException("维护任务 payload 无效。", ex);
        }
    }

    public String idempotencyKey(MaintenanceTaskPayloadV1 payload) {
        byte[] canonicalPayload = encode(payload).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPayload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void validate(MaintenanceTaskPayloadV1 payload) {
        if (payload == null || payload.scopeType() == null || payload.operation() == null) {
            throw new IllegalArgumentException("Maintenance payload is missing required fields");
        }
        if (payload.scopeType() == KnowledgeFolderRunScopeType.ALL) {
            if (payload.scopeId() != null) {
                throw new IllegalArgumentException("ALL scope must not carry scopeId");
            }
        } else if (payload.scopeId() == null || payload.scopeId().isBlank()) {
            throw new IllegalArgumentException("Folder scope requires scopeId");
        }
        if (payload.operation() == KnowledgeFolderRunOperation.IMPORT
                && (payload.folderPath() == null || payload.folderPath().isBlank())) {
            throw new IllegalArgumentException("IMPORT requires folderPath");
        }
        if (payload.operation() != KnowledgeFolderRunOperation.IMPORT && payload.folderPath() != null) {
            throw new IllegalArgumentException("Only IMPORT may carry folderPath");
        }
        if (payload.operation() == KnowledgeFolderRunOperation.ENABLE && !Boolean.TRUE.equals(payload.enabled())) {
            throw new IllegalArgumentException("ENABLE requires enabled=true");
        }
        if (payload.operation() == KnowledgeFolderRunOperation.DISABLE && !Boolean.FALSE.equals(payload.enabled())) {
            throw new IllegalArgumentException("DISABLE requires enabled=false");
        }
        if (payload.operation() != KnowledgeFolderRunOperation.ENABLE
                && payload.operation() != KnowledgeFolderRunOperation.DISABLE
                && payload.enabled() != null) {
            throw new IllegalArgumentException("Only ENABLE or DISABLE may carry enabled");
        }
        if (payload.scopeType() == KnowledgeFolderRunScopeType.ALL
                && payload.operation() != KnowledgeFolderRunOperation.REBUILD_INDEX
                && payload.operation() != KnowledgeFolderRunOperation.REPAIR_INDEX) {
            throw new IllegalArgumentException("ALL scope only supports index maintenance");
        }
    }
}
