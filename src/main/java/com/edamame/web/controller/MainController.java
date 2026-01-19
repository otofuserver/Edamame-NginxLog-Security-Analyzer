package com.edamame.web.controller;

import com.edamame.web.config.WebConfig;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.config.WebConstants;
import com.edamame.web.service.FragmentService;
import com.edamame.web.service.DataService;
import com.edamame.security.tools.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * /main 用コントローラ（サイドメニューとアプリ名/バージョンのみを表示する断片を返す）
 */
public class MainController implements HttpHandler {

    private final WebConfig webConfig;

    public MainController() {
        this.webConfig = new WebConfig();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 認証フィルターで username が設定される想定
        String username = (String) exchange.getAttribute(WebConstants.REQUEST_ATTR_USERNAME);
        if (username == null) {
            exchange.getResponseHeaders().set("Location", "/login");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        try {
            applySecurityHeaders(exchange);

            // クエリパラメータの view を優先して currentView を決定
            String query = exchange.getRequestURI().getQuery();
            String currentView = "main";
            if (query != null && !query.isEmpty()) {
                try {
                    for (String pair : query.split("&")) {
                        String[] kv = pair.split("=", 2);
                        if (kv.length == 2 && "view".equals(kv[0])) {
                            currentView = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
            Map<String, Object> data = Map.of(
                "currentUser", username,
                "currentView", currentView
            );

            // isAdmin は AuthenticationFilter がリクエスト属性に設定している
            boolean isAdmin = Boolean.TRUE.equals(exchange.getAttribute(WebConstants.REQUEST_ATTR_IS_ADMIN));

            String html = generateMainHtml(data, isAdmin);

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

            AppLogger.debug("Main fragment served");
        } catch (Exception e) {
            AppLogger.error("MainController error: " + e.getMessage());
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private void applySecurityHeaders(HttpExchange exchange) {
        Map<String, String> headers = WebSecurityUtils.getSecurityHeaders();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            exchange.getResponseHeaders().set(h.getKey(), h.getValue());
        }
    }

    private String generateMainHtml(Map<String, Object> data, boolean isAdmin) {
        String username = (String) data.getOrDefault("currentUser", "Unknown");
        String currentView = (String) data.getOrDefault("currentView", "dashboard");

        String template = webConfig.getTemplate("dashboard");
        FragmentService fragmentService = new FragmentService();
        String fragmentHtml = null;

        // サーバ側で view に応じた断片を生成する
        switch (currentView) {
            case "main" -> {
                String tpl = fragmentService.getFragmentTemplate("main");
                if (tpl != null) {
                    // main 断片はテンプレート内に APP_TITLE/APP_VERSION/MENU_HTML 等のプレースホルダを含む
                    String filled = tpl
                        .replace("{{APP_TITLE}}", WebSecurityUtils.escapeHtml(webConfig.getAppTitle()))
                        .replace("{{APP_VERSION}}", WebSecurityUtils.escapeHtml(webConfig.getAppVersion()))
                        .replace("{{MENU_HTML}}", MainController.generateMenuHtml(username, isAdmin));
                    fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"main\">" + filled + "</div>";
                }
            }
            case "dashboard" -> {
                DataService ds = new DataService();
                Map<String, Object> dashboardData = ds.getDashboardStats();
                fragmentHtml = fragmentService.dashboardFragment(dashboardData);
            }
            case "servers" -> {
                String tpl = fragmentService.getFragmentTemplate("server_management");
                if (tpl != null) {
                    fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"servers\">" + tpl + "</div>";
                }
            }
            case "users" -> {
                String tpl = fragmentService.getFragmentTemplate("user_management");
                if (tpl != null) {
                    fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"users\">" + tpl + "</div>";
                }
            }
            case "url_suppression" -> {
                String tpl = fragmentService.getFragmentTemplate("url_suppression");
                if (tpl != null) {
                    fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"url_suppression\">" + tpl + "</div>";
                }
            }
            case "test" -> fragmentHtml = fragmentService.testFragment();
            default -> {
                String tpl = fragmentService.getFragmentTemplate(currentView);
                if (tpl != null) {
                    fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"" + WebSecurityUtils.sanitizeInput(currentView) + "\">" + tpl + "</div>";
                }
            }
        }

        if (fragmentHtml == null) {
            // フォールバック: シンプルなメッセージを表示
            fragmentHtml = "<div class=\"fragment-root\" data-auto-refresh=\"0\"><div class=\"card\"><p>表示するページが見つかりません</p></div></div>";
        }

        // 最終テンプレートに挿入して返す
        String page = template
            .replace("{{APP_TITLE}}", WebSecurityUtils.escapeHtml(webConfig.getAppTitle()))
            .replace("{{APP_DESCRIPTION}}", WebSecurityUtils.escapeHtml(webConfig.getAppDescription()))
            .replace("{{APP_VERSION}}", WebSecurityUtils.escapeHtml(webConfig.getAppVersion()))
            .replace("{{CURRENT_TIME}}", WebSecurityUtils.escapeHtml(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
            .replace("{{CURRENT_USER}}", WebSecurityUtils.escapeHtml(username))
            .replace("{{CURRENT_USER_INITIAL}}", WebSecurityUtils.escapeHtml(MainController.generateUserInitial(username)))
            .replace("{{DASHBOARD_CONTENT}}", fragmentHtml)
            .replace("{{MENU_HTML}}", MainController.generateMenuHtml(username, isAdmin))
            // CSPはレスポンスヘッダで送信するためテンプレート内のプレースホルダは空文字に置換
            .replace("{{SECURITY_HEADERS}}", "")
            .replace("{{CURRENT_VIEW}}", WebSecurityUtils.escapeHtml(currentView))
            .replace("{{AUTO_REFRESH_SCRIPT}}", "");
        // サーバ側で完全にレンダリングしたページがクライアントの初期navigateで上書きされないように
        // main 要素に data-no-client-nav="true" を追加する
        try {
            String escapedView = WebSecurityUtils.escapeHtml(currentView == null ? "" : currentView);
            page = page.replace("data-view=\"" + escapedView + "\"", "data-view=\"" + escapedView + "\" data-no-client-nav=\"true\"");
        } catch (Exception ignored) {}

        return page;
    }

    /**
     * メニューHTMLを生成するユーティリティ（MainController に移動）
     * @param username 現在のユーザー名
     * @param isAdmin 管理者フラグ（サーバ側で判定して渡す）
     * @return メニューHTML
     */
    public static String generateMenuHtml(String username, boolean isAdmin) {
        boolean isOperator = isAdmin;
        try {
            if (!isOperator) {
                isOperator = new com.edamame.web.service.impl.UserServiceImpl().hasRoleIncludingHigher(username, "operator");
            }
        } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");
        sb.append("<li><a class=\"nav-link\" href=\"/main?view=dashboard\">ダッシュボード</a></li>");
        sb.append("<li><a class=\"nav-link\" href=\"/main?view=test\">サンプル</a></li>");
        if (isAdmin) sb.append("<li><a class=\"nav-link\" href=\"/main?view=users\">ユーザー管理</a></li>");
        sb.append("<li><a class=\"nav-link\" href=\"/main?view=servers\">サーバー管理</a></li>");
        sb.append("<li><a class=\"nav-link\" href=\"/main?view=url_threat\">URL脅威度</a></li>");
        if (isOperator) sb.append("<li><a class=\"nav-link\" href=\"/main?view=url_suppression\">URL抑止</a></li>");
        sb.append("</ul>");
        // ユーザー名は隠し要素として埋めてクライアント側で参照可能にする（XSS対策でエスケープ）
        sb.append("<div id=\"current-user-admin\" data-is-admin=\"")
          .append(isAdmin ? "true" : "false")
          .append("\" style=\"display:none;\"></div>");
        sb.append("<div id=\"current-user-operator\" data-is-operator=\"")
          .append(isOperator ? "true" : "false")
          .append("\" style=\"display:none;\"></div>");
        String escapedUsername = WebSecurityUtils.escapeHtml(username == null ? "" : username);
        sb.append("<div id=\"current-user-name\" data-username=\"")
          .append(escapedUsername)
          .append("\" style=\"display:none;\"></div>");
        return sb.toString();
    }

    /**
     * ユーザー名から頭文字を生成するユーティリティ（MainController に移動）
     * @param username ユーザー名
     * @return 頭文字
     */
    public static String generateUserInitial(String username) {
        if (username == null || username.trim().isEmpty()) {
            return "?";
        }

        String trimmed = username.trim().toUpperCase();

        if (trimmed.matches(".*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*")) {
            return String.valueOf(trimmed.charAt(0));
        }
        if (trimmed.length() == 1) {
            return trimmed;
        } else if (trimmed.length() >= 2) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                return parts[0].charAt(0) + "" + parts[1].charAt(0);
            } else {
                return trimmed.substring(0, 2);
            }
        }

        return trimmed;
    }

    /**
     * フルページ（テンプレート）の置換を行うユーティリティ。
     * Dashboard側で生成した断片（innerContent）を受け取り、
     * 共通テンプレートのプレースホルダを埋めて最終HTMLを返却する。
     * これによりテンプレート置換の責務をMain側に一元化します。
     *
     * @param innerContent フラグメントHTML（既にサニタイズ済み）
     * @param username 現在のユーザー名
     * @param isAdmin 管理者フラグ（AuthenticationFilter が設定）
     * @param currentView 表示中のビュー名（例: "dashboard"）
     * @return 置換済みのHTML
     */
    public static String renderFullPage(String innerContent, String username, boolean isAdmin, String currentView) {
         WebConfig cfg = new WebConfig();
         String template = cfg.getTemplate("dashboard");

        String safeUser = WebSecurityUtils.escapeHtml(username == null ? "" : username);

        return template
            .replace("{{APP_TITLE}}", WebSecurityUtils.escapeHtml(cfg.getAppTitle()))
            .replace("{{APP_DESCRIPTION}}", WebSecurityUtils.escapeHtml(cfg.getAppDescription()))
            .replace("{{APP_VERSION}}", WebSecurityUtils.escapeHtml(cfg.getAppVersion()))
            .replace("{{CURRENT_TIME}}", WebSecurityUtils.escapeHtml(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
            .replace("{{CURRENT_USER}}", safeUser)
            .replace("{{CURRENT_USER_INITIAL}}", WebSecurityUtils.escapeHtml(MainController.generateUserInitial(username)))
            .replace("{{DASHBOARD_CONTENT}}", innerContent)
            .replace("{{MENU_HTML}}", MainController.generateMenuHtml(WebSecurityUtils.sanitizeInput(username == null ? "" : username), isAdmin))
            // CSPはレスポンスヘッダで送るためテンプレート内のプレースホルダは空にする
            .replace("{{SECURITY_HEADERS}}", "")
            .replace("{{CURRENT_VIEW}}", WebSecurityUtils.escapeHtml(currentView == null ? "" : currentView))
            .replace("{{AUTO_REFRESH_SCRIPT}}", "");
     }
}
