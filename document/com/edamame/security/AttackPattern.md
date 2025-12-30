# AttackPattern

対象: `src/main/java/com/edamame/security/AttackPattern.java`

## 概要
- 攻撃パターンの定義ファイル（YAML形式）を読み込み、URL やリクエストから攻撃タイプを検出するユーティリティ。
- GitHub 上の定義ファイルとローカルファイルを比較し、自動で更新を行う機能を持つ。

## 主な機能
- YAML（`attack_patterns.yaml` / `attack_patterns_override.yaml`）の読み込みとマージ
- パターンによる攻撃タイプ検出（正規表現／簡易文字列照合）
- GitHub からの自動更新チェックとバックアップ作成
- バージョン情報取得やパターン件数取得のユーティリティ

## 挙動
- `loadYamlPatterns` により複数の YAML ファイル（メイン、オーバーライド）をマージして扱う。
- `detectAttackTypeYaml` は各パターンに対して正規表現マッチ（例外時は簡易文字列照合）を行い、該当する攻撃タイプをカンマ区切りで返す。該当なしは `normal`、エラー時は `unknown` を返す。
- `updateIfNeeded` は GitHub の生ファイルを取得し、ローカルの version と比較して差異があればバックアップを取り更新する。ネットワークや解析エラーが発生しても例外を投げずログに記録し継続する。

## 細かい指定された仕様
- YAML ファイルは Jackson の YAMLFactory でデシリアライズする。読み取り不可や解析エラー時は安全に失敗（false/"unknown" など）を返す。
- パターン定義は `pattern`, `description`, `disable`, `excludeUrls` を含む構造を想定する。
- `excludeUrls` は正規表現として評価し、正しくない正規表現は単純包含チェックにフォールバックする。
- 更新前に必ずローカルファイルのバックアップを作成する（`attack_patterns.yaml.backup.<timestamp>` など）。

## メソッド一覧と機能（主なもの）
- `public static boolean isAttackPatternsFileAvailable(String yamlPath)`
  - 指定 YAML ファイルが存在して読み取り可能かを確認し、中身にパターンが含まれるかをチェックして真偽を返す。

- `public static String getVersion(String yamlPath)`
  - YAML ファイルから `version` を取得して返す。失敗時は `unknown` を返す。

- `public static void updateIfNeeded(String yamlPath)`
  - GitHub 上の最新 YAML を取得してローカル版と比較し、必要ならバックアップ→上書き保存する。ログは AppLogger 経由で出力される。

- `public static int getPatternCountYaml(String yamlPath)`
  - YAML 内のパターン数を返す（失敗時は 0）。

- `public static AttackPatternYaml loadYamlPatterns(String... yamlPaths) throws IOException`
  - 指定された YAML ファイルを順に読み込みマージして `AttackPatternYaml` レコードを返す。

- `public static String detectAttackTypeYaml(String url, String... yamlPaths)`
  - 指定 URL に対してパターン照合を行い、検出された攻撃タイプを返す（複数はカンマ区切り）。

- `public static String getAttackTypeDescriptionYaml(String attackType, String... yamlPaths)`
  - 攻撃タイプキーから日本語説明文（description）を取得する。無ければデフォルト文字列を返す。

- `public static boolean isAttackPatternsOverrideAvailable(String overridePath)`
  - オーバーライド用 YAML ファイルの存在チェック。

- `public record AttackPatternYaml(String version, Map<String, PatternDef> patterns)`
  - 内部データ構造の定義。`PatternDef` は `pattern`, `description`, `disable`, `excludeUrls` を含む。

## その他
- YAML 解析には Jackson を使用するため、ファイルフォーマットの微妙な違いに注意すること。
- 自動更新はネットワーク障害時にローカルを壊さない挙動をする（ログ・警告を残し継続）。

## 変更履歴
- 1.0.0 - 2025-12-29: 統一フォーマットでの作成

