package com.itqianchen.agentdesign.model;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
