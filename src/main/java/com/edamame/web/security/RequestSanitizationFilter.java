package com.edamame.web.security;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.config.WebConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 共通リクエストサニタイズフィルター。
 * - 危険なクエリ・ハッシュを早期に検出し、全コンポーネントに安全なパラメータのみを渡す。
 * - サニタイズ済みクエリMapをリクエスト属性に格納する（REQUEST_ATTR_SANITIZED_QUERY）。
 */
public class RequestSanitizationFilter implements HttpHandler {

    private static final Set<String> DANGEROUS_KEYS = Set.of("redirect", "url", "next", "return", "callback");
    private static final String SAFE_PATH_REGEX = "^/[A-Za-z0-9._/#?&=%-]*$";
    private static final String SAFE_TOKEN_REGEX = "^[A-Za-z0-9._-]+$";
    private static final Set<String> FREE_TEXT_KEYS = Set.of("password", "newPassword", "confirmPassword", "currentPassword");

    private final HttpHandler next;

    public RequestSanitizationFilter(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> safeQuery = sanitize(exchange.getRequestURI());
            exchange.setAttribute(WebConstants.REQUEST_ATTR_SANITIZED_QUERY, safeQuery);
            HttpExchange sanitized = sanitizeBodyIfNeeded(exchange);
            next.handle(sanitized);
        } catch (UnsafeRequestException e) {
            AppLogger.warn("Blocked unsafe request: " + e.getMessage());
            sendBadRequest(exchange, "invalid query parameter");
        }
    }

    private Map<String, String> sanitize(URI uri) {
        Map<String, String> raw = WebSecurityUtils.parseQueryParams(uri.getRawQuery());
        Map<String, String> safe = new HashMap<>();

        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!isSafeParam(key, value)) {
                throw new UnsafeRequestException("blocked param: " + key);
            }
            safe.put(key, value);
        }
        return safe;
    }

    private boolean isSafeParam(String key, String value) {
        String lowerVal = value.toLowerCase();
        boolean dangerousKey = DANGEROUS_KEYS.contains(key);
        boolean hasAngle = value.contains("<") || value.contains(">");
        boolean hasQuote = value.contains("\"") || value.contains("'") || value.contains("`");
        boolean hasJsProto = lowerVal.contains("javascript:");
        boolean hasOnHandler = lowerVal.matches(".*on[\\w]+\\s*=.*");
        boolean allowedPath = value.isEmpty() || (value.startsWith("/") && value.matches(SAFE_PATH_REGEX));
        boolean allowedToken = value.isEmpty() || value.matches(SAFE_TOKEN_REGEX);

        if (dangerousKey) return false;
        // パスワード等は記号を許容するが、javascript: や onXXX は拒否
        if (FREE_TEXT_KEYS.contains(key)) {
            if (hasJsProto || hasOnHandler) return false;
            if (WebSecurityUtils.detectPathTraversal(value)) return false;
            return true;
        }

        if (hasAngle || hasQuote || hasJsProto || hasOnHandler) return false;
        if (!allowedPath && !allowedToken) return false;
        if (WebSecurityUtils.detectSQLInjection(value) || WebSecurityUtils.detectPathTraversal(value)) return false;
        return true;
    }

    private HttpExchange sanitizeBodyIfNeeded(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            return exchange;
        }
        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            return exchange;
        }
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        Map<String, String> form = WebSecurityUtils.parseQueryParams(new String(bodyBytes, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!isSafeParam(entry.getKey(), entry.getValue() == null ? "" : entry.getValue())) {
                throw new UnsafeRequestException("blocked body param: " + entry.getKey());
            }
        }
        return new BufferedExchange(exchange, bodyBytes);
    }

    private void sendBadRequest(HttpExchange exchange, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(400, message.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class UnsafeRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsafeRequestException(String msg) { super(msg); }
    }

    /**
     * リクエストボディを再読込可能にするための委譲ラッパー。
     */
    private static class BufferedExchange extends HttpExchange {
        private final HttpExchange delegate;
        private final InputStream requestBody;

        BufferedExchange(HttpExchange delegate, byte[] body) {
            this.delegate = delegate;
            this.requestBody = new ByteArrayInputStream(body);
        }

        @Override public Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
        @Override public Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
        @Override public URI getRequestURI() { return delegate.getRequestURI(); }
        @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
        @Override public HttpContext getHttpContext() { return delegate.getHttpContext(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return delegate.getResponseBody(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException { delegate.sendResponseHeaders(rCode, responseLength); }
        @Override public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
        @Override public int getResponseCode() { return delegate.getResponseCode(); }
        @Override public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public String getProtocol() { return delegate.getProtocol(); }
        @Override public Object getAttribute(String name) { return delegate.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
        @Override public void setStreams(InputStream i, OutputStream o) { delegate.setStreams(i, o); }
        @Override public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
    }
}
