package ru.javaroot.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

public class BoundedHttp {
    private final HttpClient client;
    private final Semaphore permits;
    private final int responseLimit;

    public BoundedHttp(int maxConcurrent, int connectTimeoutSeconds, int responseLimit) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        this.permits = new Semaphore(maxConcurrent);
        this.responseLimit = responseLimit;
    }

    public CompletableFuture<Response> send(HttpRequest request) {
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("HTTP request limit reached"));
        }
        try {
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .handle((response, error) -> {
                        try {
                            if (error != null) {
                                throw new CompletionException(error);
                            }
                            byte[] body;
                            try (InputStream input = response.body()) {
                                body = input.readNBytes(responseLimit + 1);
                            } catch (IOException exception) {
                                throw new CompletionException(exception);
                            }
                            if (body.length > responseLimit) {
                                throw new CompletionException(
                                        new IllegalStateException("HTTP response is too large"));
                            }
                            return new Response(response.statusCode(),
                                    new String(body, StandardCharsets.UTF_8));
                        } finally {
                            permits.release();
                        }
                    });
        } catch (RuntimeException exception) {
            permits.release();
            return CompletableFuture.failedFuture(exception);
        }
    }

    public record Response(int statusCode, String body) {
    }
}
