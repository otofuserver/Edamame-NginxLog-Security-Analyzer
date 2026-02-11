package com.edamame.web.service;

import com.edamame.security.db.DbService;
import com.edamame.security.tools.AppLogger;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ホワイトリスト設定を取得・更新するサービス。
 * settingsテーブルのwhitelist_modeとwhitelist_ipを扱う。
 */
public class WhitelistSettingsService {

    /**
     * ホワイトリスト設定のDTO。
     * @param whitelistMode ホワイトリストモードが有効か
     * @param whitelistIps 登録されているホワイトリストIP一覧
     */
    public record WhitelistSettings(boolean whitelistMode, List<String> whitelistIps) {}

    /**
     * 更新前後の設定と差分を保持するDTO。
     * @param before 更新前設定
     * @param after 更新後設定
     * @param addedIps 追加されたIP一覧
     * @param removedIps 削除されたIP一覧
     * @param modeChanged モード変更の有無
     */
    public record WhitelistUpdateResult(WhitelistSettings before, WhitelistSettings after,
                                        List<String> addedIps, List<String> removedIps, boolean modeChanged) {}

    /**
     * ホワイトリスト設定を取得する。
     * @return ホワイトリスト設定
     * @throws SQLException SQL例外
     */
    public WhitelistSettings load() throws SQLException {
        Map<String, Object> settings = DbService.selectWhitelistSettings();
        boolean mode = settings != null && Boolean.TRUE.equals(settings.get("whitelist_mode"));
        String ipString = settings == null ? "" : Objects.toString(settings.get("whitelist_ip"), "");
        List<String> ips = normalizeIps(List.of(ipString));
        return new WhitelistSettings(mode, ips);
    }

    /**
     * ホワイトリスト設定を更新し、差分を返す。
     * @param whitelistMode ホワイトリストモード
     * @param whitelistIps 登録するIPリスト（カンマ区切り文字列も許容）
     * @param updatedBy 更新者
     * @return 更新結果（前後設定と差分）
     * @throws SQLException SQL例外
     */
    public WhitelistUpdateResult update(boolean whitelistMode, List<String> whitelistIps, String updatedBy) throws SQLException {
        WhitelistSettings before = load();
        List<String> sanitized = normalizeIps(whitelistIps);
        String joined = String.join(",", sanitized);
        DbService.updateWhitelistSettings(whitelistMode, joined);
        WhitelistSettings after = new WhitelistSettings(whitelistMode, sanitized);

        // 差分検出
        java.util.Set<String> beforeSet = new java.util.LinkedHashSet<>(before.whitelistIps());
        java.util.Set<String> afterSet = new java.util.LinkedHashSet<>(sanitized);
        List<String> added = afterSet.stream().filter(ip -> !beforeSet.contains(ip)).toList();
        List<String> removed = beforeSet.stream().filter(ip -> !afterSet.contains(ip)).toList();
        boolean modeChanged = before.whitelistMode() != whitelistMode;

        AppLogger.info("ホワイトリスト設定を更新しました: mode=" + whitelistMode + ", added=" + added.size() + ", removed=" + removed.size() + " by=" + updatedBy);
        return new WhitelistUpdateResult(before, after, added, removed, modeChanged);
    }

    private List<String> normalizeIps(List<String> rawIps) {
        if (rawIps == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : rawIps) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] split = raw.split(",");
            for (String part : split) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                validateIp(trimmed);
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    private void validateIp(String ip) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("IPアドレスが空です");
        }
        // IP以外の文字が混入しないように軽くフィルタし、InetAddressで最終確認
        if (!ip.matches("[0-9a-fA-F:.,]+")) {
            throw new IllegalArgumentException("不正な文字を含むIPです: " + ip);
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address == null) {
                throw new IllegalArgumentException("IP解決に失敗しました: " + ip);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("IP形式が不正です: " + ip);
        }
    }
}
