package com.fiskerwarm.internet;

import android.app.Activity;
import android.content.Intent;

/** Run off the UI thread. Only allowlisted messages reach the screen. */
public final class ConfigImport {
    private ConfigImport() {}
    public enum Failure { NONE, READ, FORMAT, STORAGE }
    public static final class Result {
        public final Config config;
        public final Failure failure;
        private Result(Config config, Failure failure) { this.config = config; this.failure = failure; }
        public String message(String role) {
            String name = "remote".equals(role) ? "Remote" : "Gateway";
            switch (failure) {
                case READ: return "Не вдалося прочитати файл. Виберіть приватний " + name + "-файл із «Завантаження» повторно.";
                case FORMAT: return "Файл прочитано, але він не є коректною конфігурацією " + name + ". Виберіть Fisker-Warm-" + role + "-private.json.";
                case STORAGE: return "Файл перевірено, але не вдалося зберегти прив’язку в захищеному сховищі телефона. Імпорт не завершено.";
                default: return name + "-файл імпортовано й збережено на телефоні.";
            }
        }
    }

    public static Result run(Activity activity, Intent data, String role) {
        final String raw;
        try { raw = Ui.imported(activity, data); }
        catch (Exception e) { return new Result(null, Failure.READ); }
        final Config next;
        try { next = new Config(raw, role); }
        catch (Exception e) { return new Result(null, Failure.FORMAT); }
        try { Vault.save(activity, raw); }
        catch (Exception e) { return new Result(null, Failure.STORAGE); }
        return new Result(next, Failure.NONE);
    }
}
