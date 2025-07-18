package com.edamame.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;

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
import java.util.Iterator;
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

    private static final String GITHUB_URL = "https://raw.githubusercontent.com/otofuserver/Edamame-NginxLog-Security-Analyzer/master/attack_patterns.json";

    /**
     * URLをデコードする（%エンコーディング -> 通常文字）
     * @param url エンコードされたURL
     * @return デコードされたURL
     */
    public static String decodeUrl(String url) {
        try {
            // プラス記号をスペースに変換
            String decoded = url.replace("+", " ");

            // URLデコードを最大2回実行（二重エンコーディング対応）
            for (int i = 0; i < 2; i++) {
                decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            }

            return decoded;
        } catch (Exception e) {
            return url; // デコードに失敗した場合は元のURLを返す
        }
    }

    /**
     * URLから攻撃タイプを検出する（正規表現マッチング）
     * URLデコード後の文字列でも検査を行う
     * @param url 検査対象のURL
     * @param attackPatternsPath attack_patterns.jsonのパス
     * @param logFunc ログ出力用関数（省略可）
     * @return 攻撃タイプ（文字列、複数の場合はカンマ区切り）
     */
    public static String detectAttackType(String url, String attackPatternsPath, BiConsumer<String, String> logFunc) {
        try {
            // JSONファイルを読み込み（Jackson使用）
            ObjectMapper mapper = new ObjectMapper();
            JsonNode patterns = mapper.readTree(new File(attackPatternsPath));

            List<String> detectedAttacks = new ArrayList<>();

            // URLをデコード
            String decodedUrl = decodeUrl(url);

            // バージョンキーを除外して攻撃パターンを検査
            Iterator<Map.Entry<String, JsonNode>> fields = patterns.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String attackType = entry.getKey();
                String pattern = entry.getValue().asText();

                if ("version".equals(attackType)) { // バージョン情報は除外
                    continue;
                }

                try {
                    // 正規表現パターンをコンパイル
                    Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

                    // 元のURLとデコード後のURLの両方で正規表現チェック
                    if (regex.matcher(url).find() || regex.matcher(decodedUrl).find()) {
                        detectedAttacks.add(attackType);
                    }
                } catch (PatternSyntaxException e) {
                    // 正規表現エラーの場合は単純な文字列マッチングに代替
                    if (url.toLowerCase().contains(pattern.toLowerCase()) ||
                        decodedUrl.toLowerCase().contains(pattern.toLowerCase())) {
                        detectedAttacks.add(attackType);
                    }
                }
            }

            if (!detectedAttacks.isEmpty()) {
                return String.join(",", detectedAttacks); // 複数検出時はカンマ区切り
            } else {
                return "normal"; // 攻撃パターンが見つからない場合は正常
            }

        } catch (IOException e) {
            if (logFunc != null) {
                logFunc.accept("attack_patterns.jsonの読み込みでI/Oエラー: " + e.getMessage(), "WARN");
            }
            return "unknown"; // ファイルが見つからない場合や読み取り権限がない場合
        } catch (Exception e) {
            if (logFunc != null) {
                logFunc.accept("attack_patterns.json処理でエラー: " + e.getMessage(), "WARN");
            }
            return "unknown"; // その他のエラーの場合
        }
    }

    /**
     * GitHubからattack_patterns.jsonの最新バージョンを確認し、
     * 新しいバージョンがある場合は更新する
     * @param attackPatternsPath ローカルのattack_patterns.jsonのパス
     * @param logFunc ログ出力用関数（省略可）
     * @return 更新が実行された場合true
     */
    public static boolean updateIfNeeded(String attackPatternsPath, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            // Pathオブジェクトを一度だけ作成
            Path attackPatternsFile = Paths.get(attackPatternsPath);

            // ローカルファイルのバージョン情報を取得
            String localVersion = "";
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode localPatterns = mapper.readTree(attackPatternsFile.toFile());
                if (localPatterns.has("version")) {
                    localVersion = localPatterns.get("version").asText();
                }
            } catch (IOException e) {
                log.accept("ローカルのattack_patterns.jsonが見つからないか読み込めません: " + e.getMessage(), "WARN");
                return false;
            }

            log.accept("攻撃パターンのバージョン確認中... (ローカル: " + localVersion + ")", "INFO");

            // GitHubから最新のattack_patterns.jsonを取得
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
                    return false;
                }

                // GitHubからのJSONを解析
                ObjectMapper mapper = new ObjectMapper();
                JsonNode remotePatterns = mapper.readTree(response.body());
                String remoteVersion = remotePatterns.has("version") ? remotePatterns.get("version").asText() : "unknown";

                log.accept("GitHub版バージョン: " + remoteVersion, "DEBUG");

                // バージョン比較
                if (!localVersion.equals(remoteVersion)) {
                    log.accept("新しいバージョンが見つかりました。更新を実行します...", "INFO");

                    // ローカルファイルをバックアップ
                    try {
                        Path backupPath = Paths.get(attackPatternsPath + ".backup." + System.currentTimeMillis());
                        Files.copy(attackPatternsFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log.accept("バックアップ作成に失敗: " + e.getMessage(), "WARN");
                    }

                    // 新しいファイルを保存
                    try {
                        Files.writeString(attackPatternsFile, response.body(), StandardCharsets.UTF_8,
                                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                        log.accept("攻撃パターンファイルが正常に更新されました (v" + localVersion + " -> v" + remoteVersion + ")", "INFO");
                        return true;

                    } catch (IOException e) {
                        log.accept("攻撃パターンファイルの保存に失敗: " + e.getMessage(), "ERROR");
                        return false;
                    }
                } else {
                    log.accept("攻撃パターンファイルは最新です", "INFO");
                    return false;
                }

            } catch (IOException e) {
                log.accept("GitHubからの取得中にI/Oエラー: " + e.getMessage(), "WARN");
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // スレッドの割り込み状態を再設定
                log.accept("GitHubからの取得中に割り込みが発生しました: " + e.getMessage(), "WARN");
                return false;
            }

        } catch (Exception e) {
            log.accept("攻撃パターンのバージョン確認中にエラーが発生しました: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * attack_patterns.jsonファイルが存在するかチェックする
     * @param attackPatternsPath attack_patterns.jsonのパス
     * @return ファイルが存在し読み込み可能な場合true
     */
    public static boolean isAttackPatternsFileAvailable(String attackPatternsPath) {
        try {
            File file = new File(attackPatternsPath);
            if (!file.exists() || !file.canRead()) {
                return false;
            }

            // JSONファイルとして正常に読み込めるかチェック
            String jsonContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JSONObject patterns = new JSONObject(jsonContent);

            // 最低限の構造チェック（オブジェクトが空でないこと）
            return !patterns.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * attack_patterns.jsonからバージョン情報を取得する
     * @param attackPatternsPath attack_patterns.jsonのパス
     * @return バージョン文字列（取得できない場合は"unknown"）
     */
    public static String getVersion(String attackPatternsPath) {
        try {
            String jsonContent = Files.readString(Paths.get(attackPatternsPath), StandardCharsets.UTF_8);
            JSONObject patterns = new JSONObject(jsonContent);

            if (patterns.has("version")) {
                return patterns.getString("version");
            } else {
                return "unknown";
            }

        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * attack_patterns.jsonから攻撃パターンの数を取得する
     * @param attackPatternsPath attack_patterns.jsonのパス
     * @return パターン数（versionキーを除く）
     */
    public static int getPatternCount(String attackPatternsPath) {
        try {
            String jsonContent = Files.readString(Paths.get(attackPatternsPath), StandardCharsets.UTF_8);
            JSONObject patterns = new JSONObject(jsonContent);

            // バージョンキーを除いてカウント
            int count = 0;
            Iterator<String> keys = patterns.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"version".equals(key)) {
                    count++;
                }
            }
            return count;

        } catch (Exception e) {
            return 0;
        }
    }
}
