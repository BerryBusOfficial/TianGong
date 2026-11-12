package com.taixu.tiangong.common.transport;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.exception.HttpTransportException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class HttpTransport {

    private static final HttpClient HTTP_CLIENT;

    static {
        HTTP_CLIENT = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
        } catch (IOException e) {
            log.error("发送Post失败：{}", e.getMessage(), e);
            throw new SystemRuntimeException(e);
        } catch (InterruptedException e) {
            log.error("线程中断错误：{}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new HttpTransportException(e);
        }
    }

    public HttpResponse<InputStream> stream(HttpRequest request) {
        try {
            return HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofInputStream()
            );
        } catch (IOException e) {
            log.error("流式发送Post失败：{}", e.getMessage(), e);
            throw new SystemRuntimeException(e);
        } catch (InterruptedException e) {
            log.error("流式线程中断错误：{}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new HttpTransportException(e);
        }
    }

    public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        return HTTP_CLIENT.sendAsync(
                request, HttpResponse.BodyHandlers.ofString()
        );
    }

    public CompletableFuture<HttpResponse<InputStream>> streamAsync(HttpRequest request) {
        return HTTP_CLIENT.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream()
        );
    }

}
