FROM openjdk:21-jdk-slim

# メタデータの追加
LABEL maintainer="Edamame Security Team" \
      description="Edamame NginxLog Security Analyzer - Webフロントエンド統合版" \
      version="1.0.0"

# 日本語ロケールとタイムゾーンの設定
ENV TZ=Asia/Tokyo
ENV LANG=ja_JP.UTF-8
ENV LC_ALL=ja_JP.UTF-8
ENV LANGUAGE=ja_JP:ja

RUN apt-get update && apt-get install -y \
    tzdata \
    locales \
    && ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata \
    && echo "ja_JP.UTF-8 UTF-8" >> /etc/locale.gen \
    && locale-gen ja_JP.UTF-8 \
    && update-locale LANG=ja_JP.UTF-8

# UID/GID に合わせて nginxlog ユーザーを作成（実環境に合わせて調整）
RUN groupadd -g 113 nginxlog && useradd -u 107 -g 113 -M -s /usr/sbin/nologin nginxlog

# 必要なパッケージのインストール
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    procps \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# アプリケーション用ディレクトリの作成
WORKDIR /app

# 設定ファイル用ディレクトリの事前作成
RUN mkdir -p /run/secrets /app/config /var/log/nginx \
    && chown -R nginxlog:nginxlog /run/secrets /app/config /var/log/nginx


# JARファイルをコピー（Webフロントエンド統合版）
COPY ./NginxLogToMysql-1.0.1.jar ./nginxlog-analyzer.jar
COPY ./SetupSecureConfig-V1.0.jar ./setup-config.jar

# 実行権限の設定
RUN chown nginxlog:nginxlog /app/*.jar

# ユーザーを nginxlog に切り替え
USER nginxlog

# JVMオプションの設定（Webフロントエンド対応でメモリを増量）
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo"

# アプリケーションの設定
ENV LOG_PATH="/var/log/nginx/nginx.log"
ENV SECURE_CONFIG_PATH="/run/secrets/db_config_enc"
ENV KEY_PATH="/run/secrets/db_secret_key"
ENV SERVERS_CONFIG_PATH="/app/config/servers.conf"

# Webフロントエンド設定
ENV ENABLE_WEB_FRONTEND="true"
ENV WEB_PORT="8080"
ENV WEB_BIND_ADDRESS="0.0.0.0"

# Webダッシュボード用ポートを公開
EXPOSE 8080

# ヘルスチェック設定（Webダッシュボード対応）
#HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
 #   CMD curl -f http://localhost:8080/api/health || exit 1

# デフォルトコマンド（Web���ロントエンド統合版メインアプリケーションを実行）
CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/nginxlog-analyzer.jar"]
