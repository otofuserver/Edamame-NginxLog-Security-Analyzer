@echo off
REM Edamame Agent Installation Script for Windows
REM エージェント自動インストールスクリプト

setlocal enabledelayedexpansion

set AGENT_VERSION=1.0.1
set INSTALL_DIR=C:\Program Files\EdamameAgent
set CONFIG_DIR=C:\ProgramData\EdamameAgent
set SERVICE_NAME=EdamameAgent

echo === Edamame Agent Installer v%AGENT_VERSION% ===

REM 管理者権限チェック
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo このスクリプトは管理者権限で実行してください
    pause
    exit /b 1
)

REM Java環境チェック
java -version >nul 2>&1
if %errorLevel% neq 0 (
    echo Javaがインストールされていません。Java 11以上をインストールしてください。
    pause
    exit /b 1
)

echo ✓ Java環境が確認されました

REM ディレクトリ作成
echo インストールディレクトリを作成中...
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

REM JARファイル配置
if exist "EdamameAgent-%AGENT_VERSION%.jar" (
    echo エージェントJARファイルをコピー中...
    copy "EdamameAgent-%AGENT_VERSION%.jar" "%INSTALL_DIR%\"
) else (
    echo エラー: EdamameAgent-%AGENT_VERSION%.jar が見つかりません
    pause
    exit /b 1
)

REM 設定ファイル配置
if exist "config\agent-config-windows.json" (
    echo 設定ファイルをコピー中...
    copy "config\agent-config-windows.json" "%CONFIG_DIR%\agent-config.json"
) else (
    echo 警告: Windows用設定ファイルが見つかりません
)

REM Windowsサービス作成
echo Windowsサービスを作成中...
sc create %SERVICE_NAME% binPath= "java -jar \"%INSTALL_DIR%\EdamameAgent-%AGENT_VERSION%.jar\" \"%CONFIG_DIR%\agent-config.json\"" DisplayName= "Edamame Security Agent" start= auto

if %errorLevel% equ 0 (
    echo ✓ サービスが正常に作成されました
) else (
    echo 警告: サービス作成に失敗しました（既に存在する可能性があります）
)

REM ファイアウォール例外追加
echo Windowsファイアウォール例外を追加中...
netsh advfirewall firewall add rule name="Edamame Agent" dir=out action=allow program="%INSTALL_DIR%\EdamameAgent-%AGENT_VERSION%.jar"

echo.
echo === インストール完了 ===
echo インストール場所: %INSTALL_DIR%
echo 設定ファイル: %CONFIG_DIR%\agent-config.json
echo.
echo 設定ファイルを編集してから以下のコマンドでサービスを開始してください:
echo   sc start %SERVICE_NAME%
echo.
echo サービス状態確認:
echo   sc query %SERVICE_NAME%
echo.
echo サービス停止:
echo   sc stop %SERVICE_NAME%
echo.
echo サービス削除（アンインストール時）:
echo   sc delete %SERVICE_NAME%

pause
