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

        String content = "<div class=\"card\"><h2>概要</h2>" +
            "<p>総アクセス: " + WebSecurityUtils.escapeHtml(String.valueOf(data.getOrDefault("totalAccess", 0))) + "</p>" +
            "<p>攻撃検知: " + WebSecurityUtils.escapeHtml(String.valueOf(data.getOrDefault("attackCount", 0))) + "</p>" +
            "</div>";

        // ラップして data-auto-refresh を付与（dashboard は 30秒で自動更新）
        String wrapped = template.replace("{{CONTENT}}", content);
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
}
