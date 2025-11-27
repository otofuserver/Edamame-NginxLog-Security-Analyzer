package com.edamame.tools;

import org.json.JSONObject;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import java.security.SecureRandom;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * セキュアDB構成ウィザード
 * データベース接続情報を暗号化して保存するためのツール
 * このツールは設定専用パッケージ（com.edamame.tools）に分離されており、
 * メインアプリケーションとは独立して実行可能です。
 * バージョン: V1.0（独立バージョン管理）
 * 最終更新: 2025-07-16
 */
public class SetupSecureConfig {

    // ツール固有の定数
    private static final String TOOL_NAME = "Edamame SetupSecureConfig";
    private static final String TOOL_VERSION = "V1.1";
    private static final String TOOL_AUTHOR = "Edamame Security Team";
    private static final Logger logger = Logger.getLogger(SetupSecureConfig.class.getName());

    private static final Path KEY_PATH = Paths.get("./secret.key");
    private static final Path ENC_CONFIG_PATH = Paths.get("./db_config.enc");
    private static final Scanner scanner = new Scanner(System.in);

    /**
     * 暗号鍵を生成し、ファイルに保存する
     * @return 生成された暗号鍵（バイト配列）
     * @throws Exception 暗号鍵生成または保存時のエラー
     */
    private static byte[] generateKey() throws Exception {
        System.out.println("\n[1/4] 🔐 暗号鍵を生成中...");

        // 親ディレクトリを作成
        if (KEY_PATH.getParent() != null) {
            Files.createDirectories(KEY_PATH.getParent());
        }

        // AES-256キーを生成（SecureRandomを使用）
        try {
            SecureRandom secureRandom = new SecureRandom();
            byte[] key = new byte[32]; // 256ビット = 32バイト
            secureRandom.nextBytes(key);

            // キーをファイルに保存
            try (FileOutputStream fos = new FileOutputStream(KEY_PATH.toFile())) {
                fos.write(key);
            }

            // ファイル権限を600に設定（Unix系のみ）
            setFilePermissions(KEY_PATH);

            System.out.println("✅ 鍵を " + KEY_PATH + " に保存しました。");
            return key;
        } catch (IOException e) {
            throw new Exception("暗号鍵の生成または保存に失敗しました", e);
        }
    }

    /**
     * ファイル権限を設定する（Unix系OSの場合のみ）
     * @param path ファイルパス
     */
    private static void setFilePermissions(Path path) {
        try {
            if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
                Set<PosixFilePermission> perms = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(path, perms);
            }
        } catch (Exception e) {
            System.out.println("⚠️ ファイル権限の設定をスキップしました: " + e.getMessage());
        }
    }

    /**
     * DB接続情報を環境変数または対話入力で取得し、JSON文字列で返す
     * @return DB接続情報のJSON文字列
     * @throws Exception JSON変換時のエラー
     */
    private static String createConfig() throws Exception {
        System.out.println("\n[2/4] 🔒 DB接続情報の入力");

        Map<String, String> config = new HashMap<>();
        config.put("host", getEnvOrInput("TEST_MYSQL_HOST", "MySQLホスト名: ", false));
        config.put("port", getEnvOrInput("TEST_MYSQL_PORT", "MySQLポート番号: ", false));
        config.put("user", getEnvOrInput("TEST_MYSQL_USER", "MySQLユーザー名: ", false));
        config.put("password", getEnvOrInput("TEST_MYSQL_PASS", "MySQLパスワード: ", true));
        config.put("database", getEnvOrInput("TEST_MYSQL_DB", "データベース名: ", false));

        try {
            JSONObject json = new JSONObject(config);
            return json.toString();
        } catch (Exception e) {
            throw new Exception("JSON変換に失敗しました", e);
        }
    }

    /**
     * 環境変数から値を取得するか、対話的に入力を求める
     * @param envVar 環境変数名
     * @param prompt 入力プロンプト
     * @param isPassword パスワード入力かどうか
     * @return 入力された値
     */
    private static String getEnvOrInput(String envVar, String prompt, boolean isPassword) {
        String val = System.getenv(envVar);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }

        if (isPassword) {
            Console console = System.console();
            if (console != null) {
                char[] password = console.readPassword(prompt);
                return new String(password).trim();
            } else {
                System.out.print(prompt);
                return scanner.nextLine().trim();
            }
        } else {
            System.out.print(prompt);
            return scanner.nextLine().trim();
        }
    }

    /**
     * 設定を暗号化してファイルに保存する
     * @param key 暗号化キー
     * @param data 暗号化するデータ
     * @throws Exception 暗号化または保存時のエラー
     */
    private static void encryptAndStoreConfig(byte[] key, String data) throws Exception {
        System.out.println("\n[3/4] 🔒 設定を暗号化して保存中...");

        try {
            // AES-GCMで暗号化
            @SuppressWarnings("deprecation")
            GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            SecureRandom random = new SecureRandom();

            // 12バイトのnonce（IV）を生成
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);

            // 暗号化パラメータを設定
            AEADParameters params = new AEADParameters(new KeyParameter(key), 128, nonce);
            cipher.init(true, params);

            byte[] input = data.getBytes(StandardCharsets.UTF_8);
            byte[] output = new byte[cipher.getOutputSize(input.length)];

            int len = cipher.processBytes(input, 0, input.length, output, 0);
            len += cipher.doFinal(output, len);

            // nonceと暗号化データを結合して保存
            try (FileOutputStream fos = new FileOutputStream(ENC_CONFIG_PATH.toFile())) {
                fos.write(nonce);
                fos.write(output, 0, len);
            }

            // ファイル権限を600に設定
            setFilePermissions(ENC_CONFIG_PATH);

            System.out.println("✅ 暗号化された設定を " + ENC_CONFIG_PATH + " に保存しました。");
        } catch (DataLengthException | InvalidCipherTextException | IOException e) {
            throw new Exception("暗号化または保存に失敗しました", e);
        }
    }

    /**
     * メインメソッド
     * セキュアDB構成ウィザードを実行する
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        try {
            System.out.println("\n=== " + TOOL_NAME + " " + TOOL_VERSION + " ===");
            System.out.println("開発者: " + TOOL_AUTHOR);
            System.out.println("パッケージ: com.edamame.tools（設定ツール専用）");
            System.out.println("独立バージョン管理により、メインアプリケーションとは独立して更新されます。");

            byte[] key = generateKey();
            String configData = createConfig();
            encryptAndStoreConfig(key, configData);

            System.out.println("\n[4/4] ✅ 完了！DB接続設定は安全に保護されました。");

        } catch (Exception e) {
            System.err.println("❌ エラーが発生しました: " + e.getMessage());
            logger.log(Level.SEVERE, "SetupSecureConfigでエラーが発生しました", e);
            System.exit(1);
        } finally {
            scanner.close();
        }
    }
}
