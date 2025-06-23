FROM python:3.11-slim

# UID/GID に合わせて nginxlog ユーザーを作成
RUN groupadd -g 113 nginxlog && useradd -u 107 -g 113 -M -s /usr/sbin/nologin nginxlog

# 必要パッケージのインストール
RUN apt-get update && apt-get install -y libmariadb-dev gcc \
    && pip install --no-cache-dir mysql-connector-python cryptography \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# アプリケーションコードと秘密鍵などを追加
COPY nginx_log_to_mysql.py .
COPY db_config.enc .
COPY secret.key .

# Secrets を /run/secrets にマウントするためディレクトリ作成
RUN mkdir -p /run/secrets && chown nginxlog:nginxlog /run/secrets

# ボリューム・ログファイルを読み取り専用でマウントする前提
VOLUME ["/var/log/nginx"]

# 実行ユーザーを切り替え
USER nginxlog

CMD ["python", "nginx_log_to_mysql.py"]
