FROM openjdk:21-jdk-slim

# メタデータの追加
LABEL maintainer="Edamame Security Team" \
      description="Edamame NginxLog Security Analyzer - Java版" \
      version="1.0.33"

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

# 設定ファイルをコピー
COPY config/attack_patterns.json /app/config/attack_patterns.json

# JARファイルをコピー（バージョン番号なしの統一ファイル名）
COPY ./NginxLogToMysql.jar ./nginxlog-analyzer.jar
COPY ./SetupSecureConfig.jar ./setup-config.jar

# 実行権限の設定
RUN chown nginxlog:nginxlog /app/*.jar /app/config/*

# ユーザーを nginxlog に切り替え
USER nginxlog

# JVMオプションの設定（文字エンコーディング強制指定）
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo"

# アプリケーションの設定
ENV LOG_PATH="/var/log/nginx/nginx.log"
ENV SECURE_CONFIG_PATH="/run/secrets/db_config_enc"
ENV KEY_PATH="/run/secrets/db_secret_key"
ENV ATTACK_PATTERNS_PATH="/app/config/attack_patterns.json"

# ヘルスチェック設定
#HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
#    CMD /app/healthcheck.sh

# デフォルトコマンド（メインアプリケーションを実行）
CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/nginxlog-analyzer.jar"]
