package ru.javaroot.smpshop.hook;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

public final class HttpCli implements AutoCloseable {
    private final HttpClient client;
    private final Semaphore permits;
    private final int maxBytes;

    public HttpCli(int maxConcurrent, int connectTimeoutSec, int maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .build();
        this.permits = new Semaphore(maxConcurrent);
        this.maxBytes = maxBytes;
    }

    public CompletableFuture<Resp> send(HttpRequest req) {
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Превышен лимит HTTP-запросов"));
        }
        try {
            return client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                    .handle((resp, err) -> {
                        try {
                            if (err != null) {
                                throw new CompletionException(err);
                            }
                            byte[] body;
                            try (InputStream in = resp.body()) {
                                body = in.readNBytes(maxBytes + 1);
                            } catch (IOException ex) {
                                throw new CompletionException(ex);
                            }
                            if (body.length > maxBytes) {
                                throw new CompletionException(new IllegalStateException("Ответ HTTP превышает лимит"));
                            }
                            return new Resp(resp.statusCode(), new String(body, StandardCharsets.UTF_8));
                        } finally {
                            permits.release();
                        }
                    });
        } catch (RuntimeException ex) {
            permits.release();
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    public record Resp(int status, String body) {
    }
}
