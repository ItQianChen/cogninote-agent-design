package com.itqianchen.agentdesign.controller.chat;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.dto.chat.ChatSettingsRequest;
import com.itqianchen.agentdesign.dto.chat.ChatSettingsResponse;
import com.itqianchen.agentdesign.service.chat.ChatSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天设置控制器暴露全局聊天设置 HTTP 接口。
 * <p>控制器只负责参数校验、响应包装和服务委派，不承载设置优先级逻辑。</p>
 */
@RestController
@RequestMapping("/api/chat/settings")
public class ChatSettingsController {

    private final ChatSettingsService chatSettingsService;

    /**
     * 注入聊天设置服务。
     * <p>依赖由 Spring 统一提供，构造器本身不产生业务副作用。</p>
     */
    public ChatSettingsController(ChatSettingsService chatSettingsService) {
        this.chatSettingsService = chatSettingsService;
    }

    /**
     * 读取当前聊天设置。
     * <p>返回的是后端实际生效值，避免前端 localStorage 与后端行为不一致。</p>
     */
    @GetMapping
    public ApiResponse<ChatSettingsResponse> settings() {
        return ApiResponse.ok(chatSettingsService.settings());
    }

    /**
     * 保存聊天设置。
     * <p>当前支持追问补全策略，保存后立即影响知识库模式下的检索 query 补全。</p>
     */
    @PutMapping
    public ApiResponse<ChatSettingsResponse> update(@Valid @RequestBody ChatSettingsRequest request) {
        return ApiResponse.ok(chatSettingsService.update(request));
    }
}
