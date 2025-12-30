# attack_patterns関連管理仕様書

## 1. 概要
Edamame NginxLog Security Analyzerは、NGINXアクセスログから攻撃パターンを検知し、ModSecurityによるブロック判定・記録を行うJava製セキュリティ分析ツールです。
攻撃パターンはYAMLファイル（`attack_patterns.yaml`）で管理され、必要に応じてオーバーライドファイル（`attack_patterns_override.yaml`）でパターンの追加・上書き・無効化が可能です。

## 2. ファイル構成
- `attack_patterns.yaml`  
  メインの攻撃パターン定義ファイル。GitHubから自動更新され、バージョン管理（versionキー）を持つ。
- `attack_patterns_override.yaml`  
  ローカル独自のパターン追加・上書き・無効化用ファイル。バージョンは参照しない。

## 3. YAMLフォーマット
```yaml
version: "1.0.0"
patterns:
  SQLi:
    pattern: 'select.+from'
    description: "SQLインジェクション"
    disable: false
    excludeUrls:
      - '^/safe/query$'
      - '^/api/health.*'
  XSS:
    pattern: '<script>'
    description: "クロスサイトスクリプティング"
    disable: false
    excludeUrls: []
```
- `version`: メインファイルのみ。GitHub自動更新・バージョン比較に使用。
- `patterns`: 攻撃タイプごとの定義。
  - `pattern`: 検知用正規表現（Java Pattern準拠）。
  - `description`: 日本語説明。
  - `disable`: trueで無効化（オーバーライドで有効/無効切替可）。
  - `excludeUrls`: 例外URLパターン（正規表現、複数可）。

## 4. オーバーライド仕様
- `attack_patterns_override.yaml`は`patterns`のみ定義可能。
- 既存パターンの上書き・disable化、新規パターン追加が可能。
- `version`キーは参照しない（メインファイルのみ管理）。

## 5. Java側管理ロジック
- `AttackPattern.java`
  - `loadYamlPatterns(String... yamlPaths)`: メイン＋オーバーライドファイルをマージ。versionは最初のファイルのみ保持。
  - `detectAttackTypeYaml(String url, ...)`: URLから攻撃タイプを判定。オーバーライド・disable・excludeUrls対応。
  - `getAttackTypeDescriptionYaml(String attackType, ...)`: 日本語説明取得（オーバーライド対応）。
  - `isAttackPatternsFileAvailable(String path)`: メインファイル存在判定。
  - `isAttackPatternsOverrideAvailable(String path)`: オーバーライドファイル存在判定。
  - `getVersion(String path)`: メインファイルのversion取得。

- `NginxLogToMysql.java`
  - 起動時にattack_patterns.yamlの自動更新・バックアップ・バージョン比較を実施。
  - オーバーライドファイルの有無を判定し、ログ出力。
  - 攻撃タイプ判定時は両ファイルをマージして利用。

## 6. 自動更新・バックアップ
- 1時間ごとにGitHub上のattack_patterns.yamlを取得し、ローカルバージョンと比較。
- 新バージョンの場合はバックアップ（タイムスタンプ付）後に上書き。
- ネットワークエラー時は警告ログのみ出力し、継続動作。

## 7. エラーハンドリング
- YAMLパースエラー時は警告ログ出力、既存ファイルを維持。
- パターン定義の正規表現エラーは部分一致判定にフォールバック。
- excludeUrlsの正規表現エラーも部分一致判定にフォールバック。

## 8. コミット・ドキュメント更新ルール
- 仕様変更時は本仕様書・`specification.txt`・`CHANGELOG.md`を必ず更新。
- バージョン更新時は`attack_patterns.yaml`のversionキー・`NginxLogToMysql.java`のAPP_VERSIONも更新。

---

### コミットメッセージ例
```
docs: attack_patterns管理仕様書を追加・更新
```

---

この仕様書は`document/attack_patterns_spec.md`として管理してください。

