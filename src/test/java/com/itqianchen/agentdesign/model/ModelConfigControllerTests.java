package com.itqianchen.agentdesign.model;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-model-controller",
        "app.storage.database-path=target/test-cogninote-model-controller/cogninote.db",
        "server.address=127.0.0.1"
})
class ModelConfigControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearModelConfigs();
    }

    @Test
    void fetchModelsRequiresApiKeyAndReturnsUnifiedError() throws Exception {
        mockMvc.perform(post("/api/model-config/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "CHAT",
                                  "provider": "DASHSCOPE",
                                  "displayName": "DashScope",
                                  "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
                                  "apiKey": "",
                                  "modelName": "qwen-plus",
                                  "chatModel": "qwen-plus",
                                  "embeddingModel": "text-embedding-v4",
                                  "embeddingDimensions": 1024,
                                  "temperature": 0.7,
                                  "topK": 8,
                                  "defaultTopK": 8
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MODEL_CONFIGURATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("API Key")));
    }

    @Test
    void activeConfigsReturnsSplitModelConfigs() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/model-configs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.chat.role").value("CHAT"))
                .andExpect(jsonPath("$.data.embedding.role").value("EMBEDDING"));
    }

    @Test
    void settingsSnapshotReturnsActiveSummaryAndSelectedConfig() throws Exception {
        mockMvc.perform(get("/api/model-configs/settings").param("role", "CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("CHAT"))
                .andExpect(jsonPath("$.data.active.chat.role").value("CHAT"))
                .andExpect(jsonPath("$.data.active.embedding.role").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.selectedConfig.role").value("CHAT"));
    }

    @Test
    void settingsCreateReturnsCreatedConfigAsSelected() throws Exception {
        mockMvc.perform(post("/api/model-configs/settings/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "CHAT",
                                  "provider": "OPENAI_COMPATIBLE",
                                  "displayName": "Chat Settings",
                                  "baseUrl": "https://api.example.test/v1",
                                  "apiKey": "sk-test",
                                  "modelName": "gpt-test",
                                  "temperature": 0.4,
                                  "defaultTopK": 6
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("CHAT"))
                .andExpect(jsonPath("$.data.selectedConfig.displayName").value("Chat Settings"))
                .andExpect(jsonPath("$.data.selectedConfig.temperature").value(0.4))
                .andExpect(jsonPath("$.data.selectedConfig.defaultTopK").value(6));
    }

    @Test
    void settingsCreateAndSnapshotForceOpenAiCompatibleEmbeddingDimensionsTo1024() throws Exception {
        mockMvc.perform(post("/api/model-configs/settings/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "EMBEDDING",
                                  "provider": "OPENAI_COMPATIBLE",
                                  "displayName": "Qwen Embedding",
                                  "baseUrl": "https://api.siliconflow.cn/v1",
                                  "apiKey": "sk-test",
                                  "modelName": "Qwen/Qwen3-Embedding-8B",
                                  "embeddingDimensions": 2048
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.selectedConfig.provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.selectedConfig.embeddingDimensions").value(1024));

        mockMvc.perform(get("/api/model-configs/settings").param("role", "EMBEDDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.selectedConfig.provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.selectedConfig.embeddingDimensions").value(1024));
    }

    @Test
    void settingsDeleteOnlyConfigReturnsFallbackSelectedConfig() throws Exception {
        String body = mockMvc.perform(post("/api/model-configs/settings/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "EMBEDDING",
                                  "provider": "DASHSCOPE",
                                  "displayName": "Embedding Settings",
                                  "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
                                  "apiKey": "",
                                  "modelName": "text-embedding-v4",
                                  "embeddingDimensions": 1024
                                }
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.data.selectedConfig.id");

        mockMvc.perform(delete("/api/model-configs/settings/configs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.selectedConfig.role").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.selectedConfig.active").value(true));
    }

    @Test
    void listConfigsRejectsInvalidRoleWithUnifiedBadRequest() throws Exception {
        mockMvc.perform(get("/api/model-configs").param("role", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MODEL_CONFIGURATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid role")));
    }

    @Test
    void embeddingConnectionTestReturnsExplicitSkipMessage() throws Exception {
        mockMvc.perform(post("/api/model-configs/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "EMBEDDING",
                                  "provider": "DASHSCOPE",
                                  "displayName": "DashScope Embedding",
                                  "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
                                  "apiKey": "sk-test",
                                  "modelName": "text-embedding-v4",
                                  "embeddingDimensions": 1024
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("未发起向量调用")));
    }
}
