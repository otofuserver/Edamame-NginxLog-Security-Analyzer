#!/bin/bash
# setup_secure_config.sh - Linux/Unix用実行スクリプト
# セキュアDB構成ウィザード実行用（JAR直接起動版）

echo
echo "=== Edamame NginxLog Security Analyzer ==="
echo "セキュアDB構成ウィザードを開始します..."
echo

# Javaバージョン確認
if ! command -v java &> /dev/null; then
    echo "❌ エラー: Javaが見つかりません。Java 21以上をインストールしてください。"
    exit 1
fi

# JARファイルの存在確認（統一ファイル名）
JAR_FILE=""

# 現在のフォルダ内で検索（統一ファイル名）
if [ -f "SetupSecureConfig.jar" ]; then
    JAR_FILE="SetupSecureConfig.jar"
# buildフォルダ内も確認（バージョン付きファイル名）
elif [ -f "build/libs/SetupSecureConfig.jar" ]; then
    JAR_FILE="build/libs/SetupSecureConfig.jar"
# レガシーバージョン付きファイルも確認
else
    for f in SetupSecureConfig*.jar build/libs/SetupSecureConfig*.jar; do
        if [ -f "$f" ]; then
            JAR_FILE="$f"
            break
        fi
    done
fi

if [ -z "$JAR_FILE" ]; then
    echo "❌ エラー: SetupSecureConfig.jarが見つかりません。"
    echo "   プロジェクトをビルドしてからスクリプトを実行してください："
    echo "   ./gradlew build"
    exit 1
fi

echo "🔍 JAR発見: $JAR_FILE"
echo "🚀 セキュアDB構成ウィザードを実行中..."

# JAR実行
java -Dfile.encoding=UTF-8 -jar "$JAR_FILE"

# 実行結果の確認
if [ $? -eq 0 ]; then
    echo "✅ セキュアDB構成ウィザードが正常に完了しました。"
else
    echo "❌ エラー: 実行に失敗しました。"
    exit 1
fi
