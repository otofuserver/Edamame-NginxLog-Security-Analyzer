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
            if (is == null) return null;
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

        String content = "<div class=\"card\"><h2>概要</h2>" +
            "<p>総アクセス: " + WebSecurityUtils.escapeHtml(String.valueOf(data.getOrDefault("totalAccess", 0))) + "</p>" +
            "<p>攻撃検知: " + WebSecurityUtils.escapeHtml(String.valueOf(data.getOrDefault("attackCount", 0))) + "</p>" +
            "</div>";

        // ラップして data-auto-refresh を付与（dashboard は 30秒で自動更新）
        String wrapped = template.replace("{{CONTENT}}", content);
        return "<div class=\"fragment-root\" data-auto-refresh=\"30\">" + wrapped + "</div>";
    }

    /**
     * テスト用の空テンプレート断片
     * @return HTML断片
     */
    public String testFragment() {
        String template = loadResourceFragment("test");
        if (template != null) {
            // テンプレートがある場合は自動更新無効にして返す
            return "<div class=\"fragment-root\" data-auto-refresh=\"0\">" + template + "</div>";
        }
        return "<div class=\"fragment-root\" data-auto-refresh=\"0\"><div class=\"card\"><h2>テストページ</h2><p>これはテスト用の空テンプレートです。</p></div></div>";
    }
}
