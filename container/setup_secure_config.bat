@echo off
REM setup_secure_config.bat - Windows用実行バッチファイル
REM セキュアDB構成ウィザード実行用（JAR直接起動版）

echo.
echo === Edamame NginxLog Security Analyzer ===
echo セキュアDB構成ウィザードを開始します...
echo.

REM Javaバージョン確認
java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ❌ エラー: Javaが見つかりません。Java 21以上をインストールしてください。
    pause
    exit /b 1
)

REM JARファイルの存在確認（統一ファイル名を優先）
set JAR_FILE=

REM 統一ファイル名を最優先で確認
if exist "SetupSecureConfig.jar" (
    set JAR_FILE=SetupSecureConfig.jar
    goto :found
)

REM buildフォルダ内の統一ファイル名を確認
if exist "build\libs\SetupSecureConfig.jar" (
    set JAR_FILE=build\libs\SetupSecureConfig.jar
    goto :found
)

REM レガシーバージョン付きファイルも確認
for %%f in (SetupSecureConfig*.jar) do (
    set JAR_FILE=%%f
    goto :found
)

REM buildフォルダ内のレガシーファイルも確認
for %%f in (build\libs\SetupSecureConfig*.jar) do (
    set JAR_FILE=%%f
    goto :found
)

echo ❌ エラー: SetupSecureConfig.jarが見つかりません。
echo    プロジェクトをビルドしてからスクリプトを実行してください：
echo    gradlew.bat build
pause
exit /b 1

:found
echo 🔍 JAR発見: %JAR_FILE%
echo 🚀 セキュアDB構成ウィザードを実行中...

REM JARファイルを実行（文字エンコーディング指定）
java -Dfile.encoding=UTF-8 -jar "%JAR_FILE%"
if %ERRORLEVEL% neq 0 (
    echo ❌ エラー: 実行に失敗しました。
    pause
    exit /b 1
)

echo.
echo ✅ セキュアDB構成ウィザードが完了しました。
pause
