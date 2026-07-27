package com.itqianchen.agentdesign.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.vo.knowledge.MaintenanceTaskPayloadV1;
import org.junit.jupiter.api.Test;

class MaintenanceTaskPayloadCodecTests {

    private final MaintenanceTaskPayloadCodec codec = new MaintenanceTaskPayloadCodec(new ObjectMapper());

    @Test
    void payloadRoundTripsAndProducesStableIdempotencyKey() {
        MaintenanceTaskPayloadV1 payload = new MaintenanceTaskPayloadV1(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                "folder-1",
                KnowledgeFolderRunOperation.IMPORT,
                "D:/notes",
                true,
                null
        );

        String encoded = codec.encode(payload);

        assertThat(codec.decode(1, encoded)).isEqualTo(payload);
        assertThat(codec.idempotencyKey(payload)).isEqualTo(codec.idempotencyKey(payload));
    }

    @Test
    void unknownFieldsAndUnsupportedVersionsAreRejected() {
        assertThatThrownBy(() -> codec.decode(1, """
                {"scopeType":"KNOWLEDGE_FOLDER","scopeId":"folder-1","operation":"SYNC",
                 "folderPath":null,"recursive":true,"enabled":null,"unknown":true}
                """))
                .isInstanceOf(UnsupportedMaintenancePayloadException.class);
        assertThatThrownBy(() -> codec.decode(2, "{}"))
                .isInstanceOf(UnsupportedMaintenancePayloadException.class);
    }
}
