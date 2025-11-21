package com.edamame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.edamame.security.tools.AppLogger;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 攻撃パターン検出・更新クラス
 * attack_patterns.jsonによる攻撃タイプ検出とGitHubからの自動更新
 */
public class AttackPattern {

    private static final String GITHUB_URL = "https://raw.githubusercontent.com/otofuserver/Edamame-NginxLog-Security-Analyzer/master/container/config/attack_patterns.yaml";

    /**
     * attack_patterns.yamlファイルが存在するかチェックする
     * @param yamlPath attack_patterns.yamlのパス
     * @return ファイルが存在し読み込み可能な場合true
     */
    public static boolean isAttackPatternsFileAvailable(String yamlPath) {
        try {
            File file = new File(yamlPath);
            if (!file.exists() || !file.canRead()) {
                return false;
            }
            // YAMLファイルとして正常に読み込めるかチェック
            AttackPatternYaml yaml = loadYamlPatterns(yamlPath);
            return yaml.patterns() != null && !yaml.patterns().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * attack_patterns.yamlからバージョン情報を取得する（メインファイルのみ参照）
     * @param yamlPath attack_patterns.yamlのパス
     * @return バージョン文字列（取得できない場合は"unknown"）
     */
    public static String getVersion(String yamlPath) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            File file = new File(yamlPath);
            if (!file.exists()) return "unknown";
            AttackPatternYaml yaml = mapper.readValue(file, AttackPatternYaml.class);
            return yaml.version() != null ? yaml.version() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * GitHubからattack_patterns.yamlの最新バージョンを確認し、
     * 新しいバージョンがある場合は更新する
     * @param yamlPath ローカルのattack_patterns.yamlのパス
     */
    public static void updateIfNeeded(String yamlPath) {
        try {
            Path yamlFile = Paths.get(yamlPath);
            String localVersion = getVersion(yamlPath); // メインファイルのみ参照
            AppLogger.info("攻撃パターンのバージョン確認中... (ローカル: " + localVersion + ")");
            try (HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Edamame-NginxLog-Security-Analyzer")
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    AppLogger.warn("GitHubからの取得に失敗: HTTP " + response.statusCode());
                    return;
                }
                String body = response.body();
                if (body.trim().startsWith("<") || response.headers().firstValue("Content-Type").orElse("").contains("text/html")) {
                    AppLogger.warn("GitHubからの取得に失敗: 期待したYAMLではなくHTMLが返されました（URLやファイルの存在を確認してください）");
                    return;
                }
                // YAMLのバージョン取得
                AttackPatternYaml remoteYaml;
                try {
                    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                    remoteYaml = mapper.readValue(body, AttackPatternYaml.class);
                } catch (Exception e) {
                    AppLogger.warn("GitHubからのYAML解析に失敗: " + e.getMessage());
                    return;
                }
                String remoteVersion = remoteYaml.version() != null ? remoteYaml.version() : "unknown";
                AppLogger.debug("GitHub版バージョン: " + remoteVersion);
                if (!localVersion.equals(remoteVersion)) {
                    AppLogger.info("新しいバージョンが見つかりました。更新を実行します...");
                    try {
                        Path backupPath = Paths.get(yamlPath + ".backup." + System.currentTimeMillis());
                        Files.copy(yamlFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        AppLogger.warn("バックアップ作成に失敗: " + e.getMessage());
                    }
                    try {
                        Files.writeString(yamlFile, body, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        AppLogger.info("攻撃パターンファイルが正常に更新されました (v" + localVersion + " -> " + remoteVersion + ")");
                    } catch (IOException e) {
                        AppLogger.error("攻撃パターンファイルの保存に失敗: " + e.getMessage());
                    }
                } else {
                    AppLogger.info("攻撃パターンファイルは最新です");
                }
            }
        } catch (Exception e) {
            AppLogger.error("攻撃パターンのバージョン確認中にエラーが発生しました: " + e.getMessage());
        }
    }

    
    /**
     * attack_patterns.yamlから攻撃パターンの数を取得する
     * @param yamlPath attack_patterns.yamlのパス
     * @return パターン数（versionキーを除く）
     */
    public static int getPatternCountYaml(String yamlPath) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPath);
            return yaml.patterns() != null ? yaml.patterns().size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * YAML形式の攻撃パターン定義ファイルを読み込むためのレコード
     */
    public record AttackPatternYaml(String version, Map<String, PatternDef> patterns) {
        public record PatternDef(String pattern, String description, Boolean disable, List<String> excludeUrls) {}
    }

    /**
     * attack_patterns.yaml/override.yamlから攻撃パターン定義をマージして読み込む
     * @param yamlPaths YAMLファイルのパス（複数指定可）
     * @return AttackPatternYamlオブジェクト（マージ済み）
     */
    public static AttackPatternYaml loadYamlPatterns(String... yamlPaths) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, AttackPatternYaml.PatternDef> merged = new java.util.LinkedHashMap<>();
        String mainVersion = null;
        for (int i = 0; i < yamlPaths.length; i++) {
            String path = yamlPaths[i];
            File file = new File(path);
            if (!file.exists()) continue;
            AttackPatternYaml yaml = mapper.readValue(file, AttackPatternYaml.class);
            if (i == 0) mainVersion = yaml.version(); // 最初のファイルのみversionを保持
            if (yaml.patterns() == null) continue;
            for (var entry : yaml.patterns().entrySet()) {
                String key = entry.getKey();
                var def = entry.getValue();
                merged.put(key, def);
            }
        }
        return new AttackPatternYaml(mainVersion, merged);
    }

    /**
     * YAML版: URLから攻撃タイプを検出する（オーバーライド・無効化・例外URL対応）
     * @param url 検査対象のURL
     * @param yamlPaths attack_patterns.yaml, override.yaml等のパス（複数可）
     * @return 攻撃タイプ（文字列、複数の場合はカンマ区切り）
     */
    public static String detectAttackTypeYaml(String url, String... yamlPaths) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPaths);
            List<String> detectedAttacks = new ArrayList<>();
            for (var entry : yaml.patterns().entrySet()) {
                String attackType = entry.getKey();
                var def = entry.getValue();
                if (def == null) continue;
                if (def.disable != null && def.disable) continue; // 無効化
                boolean isExcluded = false;
                // 例外URL判定（正規表現）
                if (def.excludeUrls != null && !def.excludeUrls.isEmpty()) {
                    for (String exclude : def.excludeUrls) {
                        try {
                            Pattern excludePattern = Pattern.compile(exclude);
                            if (excludePattern.matcher(url).find()) {
                                isExcluded = true;
                                break;
                            }
                        } catch (PatternSyntaxException e) {
                            if (url.contains(exclude)) {
                                isExcluded = true;
                                break;
                            }
                        }
                    }
                }
                if (isExcluded) continue;
                String pattern = def.pattern;
                if (pattern == null || pattern.isEmpty()) continue;
                try {
                    Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                    if (regex.matcher(url).find()) {
                        detectedAttacks.add(attackType);
                    }
                } catch (PatternSyntaxException e) {
                    if (url.toLowerCase().contains(pattern.toLowerCase())) {
                        detectedAttacks.add(attackType);
                    }
                }
            }
            if (!detectedAttacks.isEmpty()) {
                return String.join(",", detectedAttacks);
            } else {
                return "normal";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * YAML版: 攻撃タイプの日本語説明を取得（オーバーライド対応）
     * @param attackType 攻撃タイプキー
     * @param yamlPaths attack_patterns.yaml, override.yaml等のパス（複数可）
     * @return 日本語説明
     */
    public static String getAttackTypeDescriptionYaml(String attackType, String... yamlPaths) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPaths);
            var def = yaml.patterns().get(attackType);
            return def != null ? def.description : "不明な攻撃タイプ";
        } catch (Exception e) {
            return "不明な攻撃タイプ";
        }
    }

    /**
     * attack_patterns_override.yamlが存在するか判定する
     * @param overridePath オーバーライドファイルのパス
     * @return 存在すればtrue、存在しなければfalse
     */
    public static boolean isAttackPatternsOverrideAvailable(String overridePath) {
        File file = new File(overridePath);
        return file.exists() && file.canRead();
    }
}
