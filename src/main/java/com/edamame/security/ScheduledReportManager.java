package com.edamame.security;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 定時レポート送信スケジューラー
 * 設定された時間に定期的に統計レポートを送信する
 */
public class ScheduledReportManager {

    private final ActionEngine actionEngine;
    private final BiConsumer<String, String> logFunction;
    private final ScheduledExecutorService scheduler;

    /**
     * コンストラクタ
     * @param actionEngine アクション実行エンジン
     * @param logFunction ログ出力関数
     */
    public ScheduledReportManager(ActionEngine actionEngine, BiConsumer<String, String> logFunction) {
        this.actionEngine = actionEngine;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        this.scheduler = Executors.newScheduledThreadPool(3);
    }

    /**
     * 定時レポートスケジュールを開始
     */
    public void startScheduledReports() {
        logFunction.accept("定時レポートスケジューラーを開始します", "INFO");

        // 日次レポート: 毎日午前8時に実行
        scheduler.scheduleAtFixedRate(
            () -> executeReport("daily", "*"),
            calculateInitialDelay(8, 0), // 午前8時
            24 * 60 * 60, // 24時間間隔
            TimeUnit.SECONDS
        );

        // 週次レポート: 毎週月曜日午前9時に実行
        scheduler.scheduleAtFixedRate(
            () -> executeReport("weekly", "*"),
            calculateInitialDelayForWeekly(1, 9, 0), // 月曜日午前9時
            7 * 24 * 60 * 60, // 1週間間隔
            TimeUnit.SECONDS
        );

        // 月次レポート: 毎月1日午前10時に実行
        scheduler.scheduleAtFixedRate(
            () -> executeReport("monthly", "*"),
            calculateInitialDelayForMonthly(1, 10, 0), // 1日午前10時
            30 * 24 * 60 * 60, // 30日間隔（概算）
            TimeUnit.SECONDS
        );

        logFunction.accept("定時レポートスケジュール設定完了", "INFO");
        logFunction.accept("- 日次レポート: 毎日午前8時", "INFO");
        logFunction.accept("- 週次レポート: 毎週月曜日午前9時", "INFO");
        logFunction.accept("- 月次レポート: 毎月1日午前10時", "INFO");
    }

    /**
     * レポート実行処理
     * @param reportType レポートタイプ（daily, weekly, monthly）
     * @param serverName 対象サーバー名
     */
    private void executeReport(String reportType, String serverName) {
        try {
            logFunction.accept(String.format("定時レポート実行開始: %s レポート (対象: %s)", reportType, serverName), "INFO");

            long startTime = System.currentTimeMillis();
            actionEngine.executeScheduledReport(serverName, reportType);
            long duration = System.currentTimeMillis() - startTime;

            logFunction.accept(String.format("定時レポート実行完了: %s レポート (処理時間: %dms)", reportType, duration), "INFO");

        } catch (Exception e) {
            logFunction.accept(String.format("定時レポート実行エラー [%s]: %s", reportType, e.getMessage()), "ERROR");
        }
    }

    /**
     * 指定時刻までの初期遅延時間を計算（日次用）
     * @param targetHour 目標時刻（時）
     * @param targetMinute 目標時刻（分）
     * @return 初期遅延時間（秒）
     */
    private long calculateInitialDelay(int targetHour, int targetMinute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);

        // 今日の目標時刻が過ぎている場合は明日に設定
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1);
        }

        long delaySeconds = java.time.Duration.between(now, target).getSeconds();

        logFunction.accept(String.format("日次レポート初回実行予定: %s (遅延: %d秒)",
            target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), delaySeconds), "DEBUG");

        return delaySeconds;
    }

    /**
     * 指定曜日・時刻までの初期遅延時間を計算（週次用）
     * @param targetDayOfWeek 目標曜日（1=月曜日, 7=日曜日）
     * @param targetHour 目標時刻（時）
     * @param targetMinute 目標時刻（分）
     * @return 初期遅延時間（秒）
     */
    private long calculateInitialDelayForWeekly(int targetDayOfWeek, int targetHour, int targetMinute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);

        // 現在の曜日を取得（1=月曜日）
        int currentDayOfWeek = now.getDayOfWeek().getValue();

        // 目標曜日までの日数を計算
        int daysUntilTarget = targetDayOfWeek - currentDayOfWeek;
        if (daysUntilTarget < 0 || (daysUntilTarget == 0 && target.isBefore(now))) {
            daysUntilTarget += 7; // 来週に設定
        }

        target = target.plusDays(daysUntilTarget);

        long delaySeconds = java.time.Duration.between(now, target).getSeconds();

        logFunction.accept(String.format("週次レポート初回実行予定: %s (遅延: %d秒)",
            target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), delaySeconds), "DEBUG");

        return delaySeconds;
    }

    /**
     * 指定日・時刻までの初期遅延時間を計算（月次用）
     * @param targetDay 目標日
     * @param targetHour 目標時刻（時）
     * @param targetMinute 目標時刻（分）
     * @return 初期遅延時間（秒）
     */
    private long calculateInitialDelayForMonthly(int targetDay, int targetHour, int targetMinute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.withDayOfMonth(targetDay).withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);

        // 今月の目標日時が過ぎている場合は来月に設定
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusMonths(1);
        }

        long delaySeconds = java.time.Duration.between(now, target).getSeconds();

        logFunction.accept(String.format("月次レポート初回実行予定: %s (遅延: %d秒)",
            target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), delaySeconds), "DEBUG");

        return delaySeconds;
    }

    /**
     * 手動でレポートを実行
     * @param reportType レポートタイプ（daily, weekly, monthly）
     * @param serverName 対象サーバー名
     */
    public void executeManualReport(String reportType, String serverName) {
        logFunction.accept(String.format("手動レポート実行: %s レポート (対象: %s)", reportType, serverName), "INFO");
        executeReport(reportType, serverName);
    }

    /**
     * スケジューラーを停止
     */
    public void shutdown() {
        logFunction.accept("定時レポートスケジューラーを停止中...", "INFO");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                logFunction.accept("定時レポートスケジューラーを強制停止しました", "WARN");
            } else {
                logFunction.accept("定時レポートスケジューラーを正常停止しました", "INFO");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logFunction.accept("定時レポートスケジューラー停止中に割り込まれました", "WARN");
        }
    }

    /**
     * スケジューラーの状態を取得
     * @return 稼働状況の文字列
     */
    public String getStatus() {
        if (scheduler.isShutdown()) {
            return "停止中";
        } else if (scheduler.isTerminated()) {
            return "終了済み";
        } else {
            return "稼働中";
        }
    }
}
