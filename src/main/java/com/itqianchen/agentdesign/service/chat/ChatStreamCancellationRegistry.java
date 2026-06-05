package com.itqianchen.agentdesign.service.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class ChatStreamCancellationRegistry {

    private static final long PENDING_CANCELLATION_TTL_MS = 30_000L;

    private final Map<String, StreamCancellation> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingCancellations = new ConcurrentHashMap<>();

    public synchronized StreamCancellation register(String requestId) {
        return register(requestId, null);
    }

    public synchronized StreamCancellation register(String requestId, Runnable onCancel) {
        cleanupExpiredPendingCancellations();
        StreamCancellation cancellation = new StreamCancellation(onCancel);
        if (requestId == null || requestId.isBlank()) {
            cancellation.dispose();
            return cancellation;
        }
        if (pendingCancellations.remove(requestId) != null) {
            // 停止请求可能先于 Flux 订阅注册到达。这里返回一个已取消句柄，
            // attach 真实订阅时会立即 dispose，同时不把一次性占位留在全局表里。
            cancellation.dispose();
            return cancellation;
        }
        StreamCancellation previous = subscriptions.put(requestId, cancellation);
        if (previous != null) {
            previous.dispose();
        }
        return cancellation;
    }

    public synchronized boolean cancel(String requestId) {
        cleanupExpiredPendingCancellations();
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        StreamCancellation cancellation = subscriptions.remove(requestId);
        if (cancellation == null) {
            // 停止按钮可能早于模型流订阅注册到达；先记下取消意图，注册时立刻取消。
            pendingCancellations.put(requestId, System.currentTimeMillis());
            return true;
        }
        cancellation.dispose();
        return true;
    }

    public synchronized void unregister(String requestId, StreamCancellation cancellation) {
        cleanupExpiredPendingCancellations();
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        if (cancellation == null) {
            subscriptions.remove(requestId);
        } else {
            subscriptions.remove(requestId, cancellation);
        }
        pendingCancellations.remove(requestId);
    }

    private void cleanupExpiredPendingCancellations() {
        long cutoff = System.currentTimeMillis() - PENDING_CANCELLATION_TTL_MS;
        pendingCancellations.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    public static final class StreamCancellation implements Disposable {

        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicBoolean disposed = new AtomicBoolean(false);
        private final Runnable onCancel;

        public StreamCancellation() {
            this(null);
        }

        public StreamCancellation(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        public void attach(Disposable actualSubscription) {
            if (actualSubscription == null) {
                return;
            }
            if (disposed.get()) {
                actualSubscription.dispose();
                return;
            }
            if (!subscription.compareAndSet(null, actualSubscription)) {
                actualSubscription.dispose();
                return;
            }
            if (disposed.get() && subscription.compareAndSet(actualSubscription, null)) {
                actualSubscription.dispose();
            }
        }

        @Override
        public void dispose() {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            Disposable actualSubscription = subscription.getAndSet(null);
            if (actualSubscription != null && !actualSubscription.isDisposed()) {
                actualSubscription.dispose();
            }
            if (onCancel != null) {
                onCancel.run();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }
}
