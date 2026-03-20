# TODO

- block_ip.service_type に旧ENUM（MANUAL欠如など）が残っている環境があり、手動作成時に `Data truncated for column 'service_type'` が発生。暫定対応は手動で `ALTER TABLE block_ip MODIFY service_type ENUM('MONITOR_BLOCK','APP_LOGIN','MANUAL') NOT NULL;` を実行すること。根本対応として、起動時スキーマ同期の確実な実行タイミングを再確認し、移行処理が必ず走るようにする。
