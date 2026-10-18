package com.assignment.eai.sender;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.Json;
import com.assignment.eai.flow.OrderInterfaceFlow;
import com.assignment.eai.flow.OrderResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST Sender - 주문자의 주문 생성 요청을 받는 연결 지점 (시나리오 1).
 * <pre>
 *  POST /api/orders   Content-Type: application/xml   (요청 XML)
 *  → 200 / 4xx / 5xx  Content-Type: application/json  (처리 결과)
 *  GET  /health       → 상태 확인
 * </pre>
 * Web Application 프레임워크 없이 JDK 내장 HttpServer 를 사용한다.
 * Sender 는 프로토콜(HTTP) 처리만 담당하고 업무 로직은 Flow 에 위임한다.
 */
public final class RestOrderSender {

    private static final Logger log = AppLog.get(RestOrderSender.class);
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)");
    public static final int DEFAULT_THREADS = 4;
    public static final int DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024;

    private final OrderInterfaceFlow flow;
    private final int port;
    private final int threads;
    private final int maxBodyBytes;
    private HttpServer server;
    private ExecutorService executor;

    public RestOrderSender(OrderInterfaceFlow flow, int port) {
        this(flow, port, DEFAULT_THREADS, DEFAULT_MAX_BODY_BYTES);
    }

    public RestOrderSender(OrderInterfaceFlow flow, int port, int threads, int maxBodyBytes) {
        this.flow = flow;
        this.port = port;
        this.threads = threads;
        this.maxBodyBytes = maxBodyBytes;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(threads);
        server.setExecutor(executor);
        server.createContext("/api/orders", this::handleOrder);
        server.createContext("/health", ex -> respond(ex, 200, Map.of("status", "UP")));
        server.start();
        log.info("REST Sender 시작: POST http://localhost:" + port + "/api/orders");
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
            executor.shutdown();
            log.info("REST Sender 종료");
        }
    }

    private void handleOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, error("POST 만 허용됩니다."));
                return;
            }
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("xml")
                    && !contentType.toLowerCase(Locale.ROOT).contains("text/plain")) {
                respond(ex, 415, error("Content-Type 은 application/xml 이어야 합니다: " + contentType));
                return;
            }
            byte[] body = readBody(ex.getRequestBody());
            if (body == null) {
                respond(ex, 413, error("요청 크기 초과(최대 " + maxBodyBytes / 1024 + "KB)"));
                return;
            }
            OrderResult result = flow.process(body, charsetOf(contentType));
            respond(ex, result.httpStatus(), result.toJson());
        } catch (RuntimeException e) {
            log.severe("요청 처리 중 예상치 못한 오류: " + e);
            respond(ex, 500, error("서버 내부 오류: " + e.getMessage()));
        }
    }

    private byte[] readBody(InputStream in) throws IOException {
        byte[] body = in.readNBytes(maxBodyBytes + 1);
        return body.length > maxBodyBytes ? null : body;
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return null;
        Matcher m = CHARSET.matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interfaceId", OrderResult.INTERFACE_ID);
        m.put("result", "FAIL");
        m.put("message", message);
        return m;
    }

    private static void respond(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
