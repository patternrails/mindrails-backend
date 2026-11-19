package com.mindrails.backend.common;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bound even chunked responses before accumulating them. */
public final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int limit;
    private Flow.Subscription subscription;

    public LimitedResponseBody(int limit) {
        this.limit = limit;
    }

    @Override public CompletionStage<byte[]> getBody() { return result; }

    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override public void onNext(List<ByteBuffer> chunks) {
        for (var chunk : chunks) {
            if ((long) bytes.size() + chunk.remaining() > limit) {
                subscription.cancel();
                result.completeExceptionally(new IllegalStateException("Response too large"));
                return;
            }
            var data = new byte[chunk.remaining()];
            chunk.get(data);
            bytes.writeBytes(data);
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
