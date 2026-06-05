package com.itqianchen.agentdesign.controller.chat;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.dto.chat.ChatSessionCreateRequest;
import com.itqianchen.agentdesign.dto.chat.ChatSessionResponse;
import com.itqianchen.agentdesign.dto.chat.ChatSessionUpdateRequest;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @GetMapping
    public ApiResponse<List<ChatSessionResponse>> listSessions() {
        return ApiResponse.ok(chatSessionService.listSessions());
    }

    @PostMapping
    public ApiResponse<ChatSessionResponse> createSession(
            @Valid @RequestBody(required = false) ChatSessionCreateRequest request
    ) {
        return ApiResponse.ok(chatSessionService.createSession(request));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ChatSessionResponse> getSession(@PathVariable String conversationId) {
        return ApiResponse.ok(chatSessionService.getSession(conversationId));
    }

    @PatchMapping("/{conversationId}")
    public ApiResponse<ChatSessionResponse> updateSession(
            @PathVariable String conversationId,
            @Valid @RequestBody ChatSessionUpdateRequest request
    ) {
        return ApiResponse.ok(chatSessionService.updateSession(conversationId, request));
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> deleteSession(@PathVariable String conversationId) {
        chatSessionService.deleteSession(conversationId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{conversationId}/messages")
    public ApiResponse<ChatSessionResponse> clearMessages(@PathVariable String conversationId) {
        return ApiResponse.ok(chatSessionService.clearMessages(conversationId));
    }
}
