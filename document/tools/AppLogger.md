# AppLogger.java 仕様書

## 役割
- アプリケーション全体で共通利用するログ出力ユーティリティ。
- タイムスタンプ・レベル付きで標準出力にログを出力。
- DEBUGレベルは環境変数`NGINX_LOG_DEBUG`で制御可能。
- staticメソッドで提供し、どのクラスからも直接呼び出し可能。

## 主なメソッド
- `public static void log(String msg, String level)`
  - 任意のレベル（INFO, WARN, ERROR, DEBUG, CRITICAL, RECOVERED等）でログ出力。
  - DEBUGレベルは`NGINX_LOG_DEBUG`が`true`のときのみ出力。
- `public static void info(String msg)`
  - INFOレベルでログ出力。
- `public static void warn(String msg)`
  - WARNレベルでログ出力。
- `public static void error(String msg)`
  - ERRORレベルでログ出力。
- `public static void debug(String msg)`
  - DEBUGレベルでログ出力。
- `public static void critical(String msg)`
  - CRITICALレベルでログ出力。
- `public static void recovered(String msg)`
  - RECOVEREDレベルでログ出力。

## ロジック
- ログ出力時に`[yyyy-MM-dd HH:mm:ss][LEVEL] メッセージ`形式で標準出力に表示。
- DEBUGレベルは環境変数`NGINX_LOG_DEBUG`が`true`でなければ出力しない。
- インスタンス化禁止（privateコンストラクタ）。

## 使用例
```java
AppLogger.log("DB接続に成功しました", "INFO");
AppLogger.error("致命的エラー発生: " + e.getMessage());
AppLogger.debug("詳細デバッグ情報: " + debugInfo);
```

## 注意事項
- すべてのログ出力はAppLogger経由で統一すること。
- 既存のBiConsumer<String, String> logFunction等は順次廃止し、AppLoggerに統合すること。
- ログレベルは大文字で指定すること（例: "INFO"、"ERROR"）。
- SLF4J/Logback等の外部ロギングライブラリ導入時は本クラスをラッパーとして拡張可能。

## バージョン履歴
- **v1.0.0**: 初期実装（標準出力・staticユーティリティ）
- **v1.1.0**: DEBUGレベルの環境変数制御を追加
- **v2.0.0**: 全アプリ共通のログ出力基盤として正式採用

