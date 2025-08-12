package com.edamame.security;

import com.edamame.security.tools.AppLogger;

import java.sql.*;
import java.util.Map;

/**
 * ホワイトリスト（IPアドレス・URL）管理クラス。
 * <p>
 * ホワイトリスト判定・登録・状態取得などの処理を集約し、
 * 今後の既存処理移行・統合のための基盤クラス。
 * DB連携や詳細ロジックは今後段階的に実装予定。
 * </p>
 * @author Edamame Team
 * @version 1.0.0
 */
public class WhitelistManager {

    private final com.edamame.security.db.DbService dbService;

    /**
     * コンストラクタ
     * @param dbService DBサービス
     */
    public WhitelistManager(com.edamame.security.db.DbService dbService) {
        this.dbService = dbService;
    }

    /**
     * クライアントIPに基づいてホワイトリスト状態を判定（複数IP対応)
     * @param clientIp クライアントIPアドレス
     * @return ホワイトリスト対象の場合true
     */
    public boolean determineWhitelistStatus(String clientIp) {
        try {
            Map<String, Object> whitelistSettings = dbService.selectWhitelistSettings();
            if (whitelistSettings != null) {
                boolean whitelistMode = Boolean.TRUE.equals(whitelistSettings.get("whitelist_mode"));
                String whitelistIp = (String) whitelistSettings.get("whitelist_ip");
                if (whitelistMode && whitelistIp != null && clientIp != null) {
                    String[] whitelistIps = whitelistIp.split(",");
                    for (String ip : whitelistIps) {
                        if (clientIp.equals(ip.trim())) {
                            AppLogger.debug("ホワイトリスト判定: モード=" + true +
                                    ", 設定IP=" + whitelistIp +
                                    ", クライアントIP=" + clientIp +
                                    " → true");
                            return true;
                        }
                    }
                    AppLogger.debug("ホワイトリスト判定: モード=" + true +
                            ", 設定IP=" + whitelistIp +
                            ", クライアントIP=" + clientIp +
                            " → false");
                    return false;
                }
            }
            AppLogger.debug("ホワイトリスト判定: 無効または設定なし (IP: " + clientIp + ")");
            return false;
        } catch (Exception e) {
            AppLogger.error("Error determining whitelist status: " + e.getMessage());
            return false;
        }
    }

    /**
     * 既存URLの再アクセス時にホワイトリスト状態を再評価（必要時のみ更新）
     */
    public void updateExistingUrlWhitelistStatusOnAccess(String serverName, String method, String fullUrl, String clientIp) {
        try {
            Boolean currentWhitelistStatus = dbService.selectIsWhitelistedFromUrlRegistry(serverName, method, fullUrl);
            if (currentWhitelistStatus == null) {
                return;
            }
            if (currentWhitelistStatus) {
                AppLogger.debug("再アクセス時URL状態確認: " + serverName + " - " + method + " " + fullUrl +
                                 " (既に安全判定済み、変更なし, IP: " + clientIp + ")");
                return;
            }
            Map<String, Object> whitelistSettings = dbService.selectWhitelistSettings();
            if (whitelistSettings != null) {
                boolean whitelistMode = Boolean.TRUE.equals(whitelistSettings.get("whitelist_mode"));
                String whitelistIp = (String) whitelistSettings.get("whitelist_ip");
                if (whitelistMode && whitelistIp != null && whitelistIp.equals(clientIp)) {
                    int affected = dbService.updateUrlWhitelistStatus(serverName, method, fullUrl);
                    if (affected > 0) {
                        AppLogger.info("再アクセス時URL安全判定: " + serverName + " - " + method + " " + fullUrl +
                                         " → safe (安全IPからアクセス: " + clientIp + ")");
                    }
                } else {
                    AppLogger.debug("再アクセス時URL状態確認: " + serverName + " - " + method + " " + fullUrl +
                                     " (unsafe維持: whitelist_mode=" + whitelistMode + ", IP: " + clientIp + ")");
                }
            }
        } catch (Exception e) {
            AppLogger.error("Error in URL whitelist status check on access: " + e.getMessage());
        }
    }
}
