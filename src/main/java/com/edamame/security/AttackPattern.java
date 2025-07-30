package com.edamame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.function.BiConsumer;
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
     * attack_patterns.yamlからバージョン情報を取得する
     * @param yamlPath attack_patterns.yamlのパス
     * @return バージョン文字列（取得できない場合は"unknown"）
     */
    public static String getVersion(String yamlPath) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPath);
            return yaml.version() != null ? yaml.version() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * GitHubからattack_patterns.yamlの最新バージョンを確認し、
     * 新しいバージョンがある場合は更新する
     * @param yamlPath ローカルのattack_patterns.yamlのパス
     * @param logFunc ログ出力用関数（省略可）
     */
    public static void updateIfNeeded(String yamlPath, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        try {
            Path yamlFile = Paths.get(yamlPath);
            String localVersion = getVersion(yamlPath);
            log.accept("攻撃パターンのバージョン確認中... (ローカル: " + localVersion + ")", "INFO");
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
                    log.accept("GitHubからの取得に失敗: HTTP " + response.statusCode(), "WARN");
                    return;
                }
                String body = response.body();
                if (body.trim().startsWith("<") || response.headers().firstValue("Content-Type").orElse("").contains("text/html")) {
                    log.accept("GitHubからの取得に失敗: 期待したYAMLではなくHTMLが返されました（URLやファイルの存在を確認してください）", "WARN");
                    return;
                }
                // YAMLのバージョン取得
                AttackPatternYaml remoteYaml;
                try {
                    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                    remoteYaml = mapper.readValue(body, AttackPatternYaml.class);
                } catch (Exception e) {
                    log.accept("GitHubからのYAML解析に失敗: " + e.getMessage(), "WARN");
                    return;
                }
                String remoteVersion = remoteYaml.version() != null ? remoteYaml.version() : "unknown";
                log.accept("GitHub版バージョン: " + remoteVersion, "DEBUG");
                if (!localVersion.equals(remoteVersion)) {
                    log.accept("新しいバージョンが見つかりました。更新を実行します...", "INFO");
                    try {
                        Path backupPath = Paths.get(yamlPath + ".backup." + System.currentTimeMillis());
                        Files.copy(yamlFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log.accept("バックアップ作成に失敗: " + e.getMessage(), "WARN");
                    }
                    try {
                        Files.writeString(yamlFile, body, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        log.accept("攻撃パターンファイルが正常に更新されました (v" + localVersion + " -> " + remoteVersion + ")", "INFO");
                    } catch (IOException e) {
                        log.accept("攻撃パターンファイルの保存に失敗: " + e.getMessage(), "ERROR");
                    }
                } else {
                    log.accept("攻撃パターンファイルは最新です", "INFO");
                }
            }
        } catch (Exception e) {
            log.accept("攻撃パターンのバージョン確認中にエラーが発生しました: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * URLをデコードする（%エンコーディング -> 通常文字）
     * @param url エンコードされたURL
     * @return デコードされたURL
     */
    public static String decodeUrl(String url) {
        try {
            String decoded = url.replace("+", " ");
            for (int i = 0; i < 2; i++) {
                decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            }
            return decoded;
        } catch (Exception e) {
            return url;
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
        public record PatternDef(String pattern, String description) {}
    }

    /**
     * attack_patterns.yamlから攻撃パターン定義を読み込む
     * @param yamlPath YAMLファイルのパス
     * @return AttackPatternYamlオブジェクト
     */
    public static AttackPatternYaml loadYamlPatterns(String yamlPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlPath), AttackPatternYaml.class);
    }

    /**
     * YAML版: 攻撃タイプの日本語説明を取得
     * @param attackType 攻撃タイプキー
     * @param yamlPath attack_patterns.yamlのパス
     * @return 日本語説明
     */
    public static String getAttackTypeDescriptionYaml(String attackType, String yamlPath) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPath);
            var def = yaml.patterns().get(attackType);
            return def != null ? def.description() : "不明な攻撃タイプ";
        } catch (Exception e) {
            return "不明な攻撃タイプ";
        }
    }

    /**
     * YAML版: URLから攻撃タイプを検出する
     * @param url 検査対象のURL
     * @param yamlPath attack_patterns.yamlのパス
     * @param logFunc ログ出力用関数（省略可）
     * @return 攻撃タイプ（文字列、複数の場合はカンマ区切り）
     */
    public static String detectAttackTypeYaml(String url, String yamlPath, BiConsumer<String, String> logFunc) {
        try {
            AttackPatternYaml yaml = loadYamlPatterns(yamlPath);
            List<String> detectedAttacks = new ArrayList<>();
            String decodedUrl = decodeUrl(url);
            for (var entry : yaml.patterns().entrySet()) {
                String attackType = entry.getKey();
                String pattern = entry.getValue().pattern();
                try {
                    Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                    if (regex.matcher(url).find() || regex.matcher(decodedUrl).find()) {
                        detectedAttacks.add(attackType);
                    }
                } catch (PatternSyntaxException e) {
                    if (url.toLowerCase().contains(pattern.toLowerCase()) ||
                        decodedUrl.toLowerCase().contains(pattern.toLowerCase())) {
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
            if (logFunc != null) {
                logFunc.accept("attack_patterns.yamlの読み込みでエラー: " + e.getMessage(), "WARN");
            }
            return "unknown";
        }
    }
}
