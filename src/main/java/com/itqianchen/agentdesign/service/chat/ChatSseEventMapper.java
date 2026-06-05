package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentEvent;
import com.itqianchen.agentdesign.dto.chat.ChatDeltaEvent;
import com.itqianchen.agentdesign.dto.chat.ChatDoneEvent;
import com.itqianchen.agentdesign.dto.chat.ChatErrorEvent;
import com.itqianchen.agentdesign.dto.chat.ChatMetaEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ChatSseEventMapper {

    private static final Logger log = LoggerFactory.getLogger(ChatSseEventMapper.class);

    public void subscribe(SseEmitter emitter, AgentChatStream stream) {
        AtomicBoolean completed = new AtomicBoolean(false);
        if (!sendSafely(emitter, completed, new AgentEvent.Meta(
                stream.conversationId(),
                stream.retrievalMode(),
                stream.sources()
        ))) {
            return;
        }

        stream.answer().subscribe(
                text -> sendSafely(emitter, completed, new AgentEvent.Delta(text)),
                error -> {
                    log.warn("agent_chat_stream_failed requestId={} conversationId={}",
                            stream.requestId(),
                            stream.conversationId(),
                            error
                    );
                    sendSafely(emitter, completed, new AgentEvent.Error(error.getMessage()));
                    completeSafely(emitter, completed);
                },
                () -> {
                    sendSafely(emitter, completed, new AgentEvent.Done(null));
                    completeSafely(emitter, completed);
                }
        );
    }

    private static boolean sendSafely(SseEmitter emitter, AtomicBoolean completed, AgentEvent event) {
        if (completed.get()) {
            return false;
        }
        try {
            send(emitter, event);
            return true;
        } catch (IOException ex) {
            completeWithErrorSafely(emitter, completed, ex);
            return false;
        } catch (IllegalStateException ex) {
            // 客户端断开或 emitter 已关闭时，Spring 可能抛出 IllegalStateException。
            // 这里统一标记完成，避免 Reactor 回调线程继续重复 complete 产生日志噪音。
            completeWithErrorSafely(emitter, completed, ex);
            return false;
        }
    }

    private static void completeSafely(SseEmitter emitter, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // emitter 可能已被容器或 completeWithError 关闭。
        }
    }

    private static void completeWithErrorSafely(SseEmitter emitter, AtomicBoolean completed, Exception ex) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.completeWithError(ex);
        } catch (IllegalStateException ignored) {
            // emitter 可能已被容器关闭，避免二次完成异常污染日志。
        }
    }

    private static void send(SseEmitter emitter, AgentEvent event) throws IOException {
        if (event instanceof AgentEvent.Meta meta) {
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(new ChatMetaEvent(meta.conversationId(), meta.retrievalMode(), meta.sources())));
            return;
        }
        if (event instanceof AgentEvent.Delta delta) {
            emitter.send(SseEmitter.event()
                    .name("delta")
                    .data(new ChatDeltaEvent(delta.text())));
            return;
        }
        if (event instanceof AgentEvent.Done done) {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(new ChatDoneEvent(done.usage())));
            return;
        }
        if (event instanceof AgentEvent.Error error) {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(new ChatErrorEvent(error.message())));
        }
    }
}
