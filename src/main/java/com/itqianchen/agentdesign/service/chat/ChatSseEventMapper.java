package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.vo.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.interfaces.agent.AgentEvent;
import com.itqianchen.agentdesign.domain.dto.chat.ChatDeltaEvent;
import com.itqianchen.agentdesign.domain.dto.chat.ChatDoneEvent;
import com.itqianchen.agentdesign.domain.dto.chat.ChatErrorEvent;
import com.itqianchen.agentdesign.domain.dto.chat.ChatMetaEvent;
import com.itqianchen.agentdesign.domain.dto.chat.ChatReasoningEvent;
import com.itqianchen.agentdesign.service.chat.ChatStreamCancellationRegistry.StreamCancellation;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 将 Agent 事件流桥接为 Spring MVC SSE 响应。
 *
 * <p>SSE 连接只是浏览器观察通道，普通断线不自动取消模型生成；只有显式停止请求才会通过
 * {@link ChatStreamCancellationRegistry} 取消底层订阅。</p>
 */
@Component
public class ChatSseEventMapper {

    private static final Logger log = LoggerFactory.getLogger(ChatSseEventMapper.class);

    private final ChatStreamCancellationRegistry cancellationRegistry;

    /**
     * 注入流取消注册表。
     *
     * @param cancellationRegistry 聊天流取消注册表
     */
    public ChatSseEventMapper(ChatStreamCancellationRegistry cancellationRegistry) {
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * 订阅 Agent 输出并按 meta/delta/done/error 事件写入前端 SSE。
     *
     * @param emitter Spring MVC SSE emitter
     * @param stream Agent 输出流和元数据
     */
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
                stream.sources(),
                stream.contextUsage()
        ));

        StreamCancellation cancellation = cancellationRegistry.register(stream.requestId(), stream.onCancel());
        if (cancellation.isDisposed()) {
            // 停止请求可能先于模型订阅注册到达；此时不要再启动模型调用。
            completeSafely(emitter, completed);
            return;
        }
        Disposable toolEventSubscription = stream.toolEvents()
                .subscribe(
                        event -> sendSafely(emitter, completed, new AgentEvent.Tool(event)),
                        error -> log.warn("agent_chat_tool_event_failed requestId={} conversationId={}",
                                stream.requestId(),
                                stream.conversationId(),
                                error
                        )
                );
        AtomicBoolean reasoningStarted = new AtomicBoolean(false);
        Disposable reasoningSubscription = stream.reasoning()
                .subscribe(reasoning -> {
                    reasoningStarted.set(true);
                    sendSafely(emitter, completed, new AgentEvent.Delta(null, reasoning));
                });
        stream.answer()
                .doOnSubscribe(subscription -> cancellation.attach(cancellationHandle(subscription)))
                .subscribe(
                        text -> sendSafely(emitter, completed, new AgentEvent.Delta(text, null)),
                        error -> {
                            log.warn("agent_chat_stream_failed requestId={} conversationId={}",
                                    stream.requestId(),
                                    stream.conversationId(),
                                    error
                            );
                            if (reasoningStarted.get()) {
                                sendSafely(emitter, completed, new AgentEvent.Delta(null, ""));
                            }
                            sendSafely(emitter, completed, new AgentEvent.Error(error.getMessage()));
                            completeSafely(emitter, completed);
                            toolEventSubscription.dispose();
                            reasoningSubscription.dispose();
                            cancellationRegistry.unregister(stream.requestId(), cancellation);
                        },
                        () -> {
                            if (reasoningStarted.get()) {
                                sendSafely(emitter, completed, new AgentEvent.Delta(null, ""));
                            }
                            sendSafely(emitter, completed, new AgentEvent.Done(null, stream.currentContextUsage()));
                            completeSafely(emitter, completed);
                            toolEventSubscription.dispose();
                            reasoningSubscription.dispose();
                            cancellationRegistry.unregister(stream.requestId(), cancellation);
                        }
                );
        // SSE 是前端观察通道，不等于生成任务本身。普通刷新、切页或连接断开时，
        // 这里仍保持模型流消费到完成，方便后续聊天记忆把完整 assistant 消息落库。
    }

    /**
     * 将 Reactive Streams Subscription 包装成 Reactor Disposable。
     *
     * @param subscription 底层订阅
     * @return 可交给取消注册表的句柄
     */
    private static Disposable cancellationHandle(Subscription subscription) {
        AtomicBoolean disposed = new AtomicBoolean(false);
        return new Disposable() {
            /**
             * 取消底层模型流订阅。
             *
             * <p>取消动作需要幂等，停止请求和 Reactor 回调可能在不同线程同时触发。</p>
             */
            @Override
            public void dispose() {
                if (disposed.compareAndSet(false, true)) {
                    subscription.cancel();
                }
            }

            /**
             * 返回取消句柄是否已经生效。
             *
             * @return 已取消时为 true
             */
            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
    }

    /**
     * 安全发送单个 Agent 事件。
     *
     * <p>发送失败会关闭 emitter 并标记完成，避免后续流回调重复写已经关闭的响应。</p>
     *
     * @param emitter SSE emitter
     * @param completed SSE 是否已完成
     * @param event Agent 事件
     * @return 是否发送成功
     */
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

    /**
     * 安全完成 SSE 响应。
     *
     * @param emitter SSE emitter
     * @param completed SSE 是否已完成
     */
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

    /**
     * 发送失败后关闭 SSE 响应。
     *
     * <p>不能调用 completeWithError，否则全局异常处理会尝试把 JSON API 响应写入 SSE 流。</p>
     *
     * @param emitter SSE emitter
     * @param completed SSE 是否已完成
     * @param ex 发送异常
     */
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

    /**
     * 将领域事件写成具体 SSE 事件。
     *
     * @param emitter SSE emitter
     * @param event Agent 事件
     * @throws IOException 当底层响应写入失败时抛出
     */
    private static void send(SseEmitter emitter, AgentEvent event) throws IOException {
        if (event instanceof AgentEvent.Meta meta) {
            // meta 必须先发，前端依赖 requestId、conversationId 和来源列表初始化本地流状态。
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(new ChatMetaEvent(
                            meta.requestId(),
                            meta.conversationId(),
                            meta.retrievalMode(),
                            meta.sources(),
                            meta.contextUsage()
                    )));
            return;
        }
        if (event instanceof AgentEvent.Delta delta) {
            if (delta.reasoning() != null) {
                emitter.send(SseEmitter.event()
                        .name("reasoning")
                        .data(new ChatReasoningEvent(delta.reasoning(),
                                delta.reasoning().isEmpty() ? "done" : "streaming")));
            }
            if (delta.text() == null || delta.text().isEmpty()) {
                return;
            }
            emitter.send(SseEmitter.event()
                    .name("delta")
                    .data(new ChatDeltaEvent(delta.text())));
            return;
        }
        if (event instanceof AgentEvent.Tool tool) {
            emitter.send(SseEmitter.event()
                    .name("tool")
                    .data(tool.event()));
            return;
        }
        if (event instanceof AgentEvent.Done done) {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(new ChatDoneEvent(done.usage(), done.contextUsage())));
            return;
        }
        if (event instanceof AgentEvent.Error error) {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(new ChatErrorEvent(error.message())));
        }
    }
}
