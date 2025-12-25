package com.edamame.web.dto;

import java.time.LocalDateTime;

/**
 * ユーザー情報を表すDTO
 * Jackson等のシリアライズ/デシリアライズで使用されるため、
 * デフォルトコンストラクタとgetter/setterを必ず実装する。
 */
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private LocalDateTime lastLogin;
    private boolean enabled;

    // Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
    public UserDto() {}

    public UserDto(String username, String email, LocalDateTime lastLogin, boolean enabled) {
        this.username = username;
        this.email = email;
        this.lastLogin = lastLogin;
        this.enabled = enabled;
    }

    public UserDto(Long id, String username, String email, LocalDateTime lastLogin, boolean enabled) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.lastLogin = lastLogin;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
