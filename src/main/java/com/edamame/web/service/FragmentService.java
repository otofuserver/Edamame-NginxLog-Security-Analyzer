package com.edamame.web.service;

import com.edamame.web.security.WebSecurityUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;

/**
 * フラグメント生成サービス
 * サーバ側で返却するHTML断片（mainコンテナ部分）を一元管理する
 */
public class FragmentService {

    /**
     * デフォルトコンストラクタ
     */
    public FragmentService() {
        // 将来的にテンプレートキャッシュ等を追加予定
    }

    private String loadResourceFragment(String name) {
        String resourcePath = "fragments/" + name + ".html";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                // クラスパスにリソースがない場合、開発環境向けにワークスペースの src/main/resources を参照してみる
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get("src", "main", "resources", resourcePath);
                    if (java.nio.file.Files.exists(p)) {
                        try (InputStream fis = java.nio.file.Files.newInputStream(p)) {
                            try (java.util.Scanner s = new java.util.Scanner(fis, StandardCharsets.UTF_8)) {
                                s.useDelimiter("\\A");
                                return s.hasNext() ? s.next() : "";
                            }
                        }
                    }
                } catch (Exception ex) {
                    // フォールバック失敗は無視して null を返す
                }
                return null;
            }
            try (Scanner s = new Scanner(is, StandardCharsets.UTF_8)) {
                s.useDelimiter("\\A");
                return s.hasNext() ? s.next() : "";
            }
        } catch (Exception e) {
            // ログは最小化（AppLogger未参照を避ける）
            return null;
        }
    }

    /**
     * 指定されたフラグメントリソースを取得する（外部呼び出し用）
     * @param name フラグメント名（fragments/{name}.html）
     * @return HTML文字列、存在しない場合はnull
     */
    public String getFragmentTemplate(String name) {
        return loadResourceFragment(name);
    }

    /**
     * ダッシュボード断片を生成する
     * @param data ダッシュボードデータ（null許容）
     * @return HTML断片（mainに挿入する部分）
     */
    public String dashboardFragment(Map<String, Object> data) {
        String template = loadResourceFragment("dashboard");
        if (template == null) {
            // フォールバック
            // フラグメント単位の自動更新はここでは無効（0秒）
            return "<div class=\"fragment-root\" data-auto-refresh=\"0\"><div class=\"card\"><p>データがありません</p></div></div>";
        }

        // データの安全な取得（存在しないキーは空や0で扱う）
        int totalAccess = safeInt(data, "totalAccess");
        int totalAttacks = safeInt(data, "attackCount");
        int modsecBlocks = safeInt(data, "modsecBlocks");
        Object serverStatsObj = data == null ? null : data.get("serverStats");
        Object recentAlertsObj = data == null ? null : data.get("recentAlerts");
        // 攻撃タイプ別統計はダッシュボードから削除（集計範囲が他と一致しないため）

        // 統計カード
        StringBuilder content = new StringBuilder();
        content.append("<div class=\"card dashboard-stats\">\n");
        content.append("<div class=\"stats-grid\">\n");
        content.append("<div class=\"stat-value-item stat-access\"><span class=\"stat-number\">")
               .append(WebSecurityUtils.escapeHtml(String.valueOf(totalAccess)))
               .append("</span><div class=\"stat-label\">総アクセス</div></div>\n");
        content.append("<div class=\"stat-value-item stat-attack\"><span class=\"stat-number\">")
               .append(WebSecurityUtils.escapeHtml(String.valueOf(totalAttacks)))
               .append("</span><div class=\"stat-label\">攻撃検知</div></div>\n");
        content.append("<div class=\"stat-value-item stat-block\"><span class=\"stat-number\">")
               .append(WebSecurityUtils.escapeHtml(String.valueOf(modsecBlocks)))
               .append("</span><div class=\"stat-label\">ModSecブロック</div></div>\n");
        content.append("</div>\n");
        content.append("</div>\n");

        // サーバ一覧（簡易表示）
        content.append("<div class=\"card\"><h3>サーバー一覧</h3><div class=\"server-list\">\n");
        if (serverStatsObj instanceof java.util.List<?> serverStats && !serverStats.isEmpty()) {
            for (Object sObj : serverStats) {
                if (!(sObj instanceof java.util.Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> s = (java.util.Map<String, Object>) sObj;
                String name = WebSecurityUtils.sanitizeInput((String) s.getOrDefault("serverName", "unknown"));
                String status = WebSecurityUtils.sanitizeInput((String) s.getOrDefault("status", "unknown"));
                String lastLog = WebSecurityUtils.sanitizeInput((String) s.getOrDefault("lastLogReceived", "未記録"));
                // 本日のアクセス数は環境によりキー名が異なる（todayAccessCount, today_access_count, todayAccess, totalAccessなど）
                Object todayObj = s.get("todayAccessCount");
                if (todayObj == null) todayObj = s.get("today_access_count");
                if (todayObj == null) todayObj = s.get("todayAccess");
                if (todayObj == null) todayObj = s.get("totalAccess"); // 最終フォールバック
                String todayAccess = WebSecurityUtils.escapeHtml(String.valueOf(toInt(todayObj)));
                content.append("<div class=\"server-item\"><strong>")
                       .append(name)
                       .append("</strong><div class=\"server-meta\">")
                       .append(lastLog)
                       .append(" | 本日のアクセス数:")
                       .append(todayAccess)
                       .append("</div><div class=\"server-status\">")
                       .append(status)
                       .append("</div></div>\n");
            }
        } else {
            content.append("<div>サーバー情報がありません</div>\n");
        }
        content.append("</div></div>\n");

        // 最近のアラート
        content.append("<div class=\"card\"><h3>最近のアラート</h3>");
        if (recentAlertsObj instanceof java.util.List<?> alerts && !alerts.isEmpty()) {
            content.append("<div class=\"alerts-list\">\n");
            int displayed = 0;
            for (Object aObj : alerts) {
                if (displayed >= 8) break; // 表示上限
                if (!(aObj instanceof java.util.Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> a = (java.util.Map<String, Object>) aObj;
                String time = WebSecurityUtils.sanitizeInput((String) a.getOrDefault("accessTime", ""));
                String serverName = WebSecurityUtils.sanitizeInput((String) a.getOrDefault("serverName", ""));
                String ip = WebSecurityUtils.sanitizeInput((String) a.getOrDefault("ipAddress", ""));
                // URL は sanitizeUrlForDisplay で表示用にサニタイズ済み（'/' を復元している）
                String url = WebSecurityUtils.sanitizeUrlForDisplay((String) a.getOrDefault("url", ""));
                String attackType = WebSecurityUtils.sanitizeInput((String) a.getOrDefault("attackType", ""));
                String displayUrl = url.length() > 80 ? url.substring(0,80) + "..." : url;
                content.append("<div class=\"alert-item\"><div class=\"alert-header\">")
                       .append(time)
                       .append(" - ")
                       .append(serverName)
                       .append("</div><div class=\"alert-body\">")
                       .append(ip)
                       .append(" からの攻撃: <strong>")
                       .append(attackType)
                       .append("</strong><br><small>URL: ")
                       .append(displayUrl)
                       .append("</small></div></div>\n");
                displayed++;
            }
            content.append("</div>");
        } else {
            content.append("<div>最近のアラートはありません</div>");
        }
        content.append("</div>\n");

        // ラップして data-auto-refresh を付与（dashboard は 30秒で自動更新）
        String wrapped = template.replace("{{CONTENT}}", content.toString());
        // data-fragment-name を付与してクライアント側から再取得できるようにする
        return "<div class=\"fragment-root\" data-auto-refresh=\"30\" data-fragment-name=\"dashboard\">" + wrapped + "</div>";
    }

    /**
     * テスト用の空テンプレート断片
     * @return HTML断片
     */
    public String testFragment() {
        String template = loadResourceFragment("test");
        if (template != null) {
            // テンプレートがある場合は自動更新無効にして返す
            // data-fragment-name を付与（自動更新は無効）
            return "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"test\">" + template + "</div>";
        }
        return "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"test\"><div class=\"card\"><h2>テストページ</h2><p>これはテスト用の空テンプレートです。</p></div></div>";
    }

    /**
     * 安全に整数を取得するユーティリティ（nullや非数値は0を返す）
     */
    private int safeInt(Map<String, Object> data, String key) {
        if (data == null) return 0;
        Object o = data.get(key);
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /**
     * オブジェクトを安全に整数に変換するユーティリティ。
     * null や非数値は 0 を返す。
     */
    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
