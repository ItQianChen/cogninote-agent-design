package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentEvent;
import com.itqianchen.agentdesign.dto.chat.ChatDeltaEvent;
import com.itqianchen.agentdesign.dto.chat.ChatDoneEvent;
import com.itqianchen.agentdesign.dto.chat.ChatErrorEvent;
import com.itqianchen.agentdesign.dto.chat.ChatMetaEvent;
import com.itqianchen.agentdesign.service.chat.ChatStreamCancellationRegistry.StreamCancellation;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@Component
public class ChatSseEventMapper {

    private static final Logger log = LoggerFactory.getLogger(ChatSseEventMapper.class);

    private final ChatStreamCancellationRegistry cancellationRegistry;

    public ChatSseEventMapper(ChatStreamCancellationRegistry cancellationRegistry) {
        this.cancellationRegistry = cancellationRegistry;
    }

    public void subscribe(SseEmitter emitter, AgentChatStream stream) {
        AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onCompletion(() -> {
            completed.set(true);
        });
        emitter.onTimeout(() -> {
            completed.set(true);
            log.warn("agent_chat_sse_timeout requestId={} conversationId={}", stream.requestId(), stream.conversationId());
        });
        emitter.onError(error -> {
            completed.set(true);
            log.warn("agent_chat_sse_closed requestId={} conversationId={}", stream.requestId(), stream.conversationId(), error);
        });

        sendSafely(emitter, completed, new AgentEvent.Meta(
                stream.requestId(),
                stream.conversationId(),
                stream.retrievalMode(),
                stream.sources()
        ));

        StreamCancellation cancellation = cancellationRegistry.register(stream.requestId(), stream.onCancel());
        if (cancellation.isDisposed()) {
            // 停止请求可能先于模型订阅注册到达；此时不要再启动模型调用。
            completeSafely(emitter, completed);
            return;
        }
        stream.answer()
                .doOnSubscribe(subscription -> cancellation.attach(cancellationHandle(subscription)))
                .subscribe(
                        text -> sendSafely(emitter, completed, new AgentEvent.Delta(text)),
                        error -> {
                            log.warn("agent_chat_stream_failed requestId={} conversationId={}",
                                    stream.requestId(),
                                    stream.conversationId(),
                                    error
                            );
                            sendSafely(emitter, completed, new AgentEvent.Error(error.getMessage()));
                            completeSafely(emitter, completed);
                            cancellationRegistry.unregister(stream.requestId(), cancellation);
                        },
                        () -> {
                            sendSafely(emitter, completed, new AgentEvent.Done(null));
                            completeSafely(emitter, completed);
                            cancellationRegistry.unregister(stream.requestId(), cancellation);
                        }
                );
        // SSE 是前端观察通道，不等于生成任务本身。普通刷新、切页或连接断开时，
        // 这里仍保持模型流消费到完成，方便后续聊天记忆把完整 assistant 消息落库。
    }

    private static Disposable cancellationHandle(Subscription subscription) {
        AtomicBoolean disposed = new AtomicBoolean(false);
        return new Disposable() {
            @Override
            public void dispose() {
                if (disposed.compareAndSet(false, true)) {
                    subscription.cancel();
                }
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
    }

    private static boolean sendSafely(SseEmitter emitter, AtomicBoolean completed, AgentEvent event) {
        if (completed.get()) {
            return false;
        }
        try {
            send(emitter, event);
            return true;
        } catch (IOException ex) {
            closeAfterSendFailure(emitter, completed, ex);
            return false;
        } catch (IllegalStateException ex) {
            // 客户端断开或 emitter 已关闭时，Spring 可能抛出 IllegalStateException。
            // 这里统一标记完成，避免 Reactor 回调线程继续重复发送产生日志噪音。
            closeAfterSendFailure(emitter, completed, ex);
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

    private static void closeAfterSendFailure(SseEmitter emitter, AtomicBoolean completed, Exception ex) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        log.debug("agent_chat_sse_send_failed", ex);
        try {
            /*
             * 这里不能调用 completeWithError。SSE 响应头已经是 text/event-stream，
             * completeWithError 会触发 Spring MVC 异常分发，最终让全局 ApiResponse
             * 尝试写入 SSE 流，产生 HttpMessageNotWritableException。
             */
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // emitter 可能已被容器关闭，避免二次完成异常污染日志。
        }
    }

    private static void send(SseEmitter emitter, AgentEvent event) throws IOException {
        if (event instanceof AgentEvent.Meta meta) {
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(new ChatMetaEvent(meta.requestId(), meta.conversationId(), meta.retrievalMode(), meta.sources())));
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
