package br.com.js.mailsender.domain.model;

import java.util.regex.Pattern;

public record Email(String value) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        var normalizado = value.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalizado).matches()) {
            throw new IllegalArgumentException("Invalid email: " + value);
        }
        value = normalizado;
    }

    public static Email of(String value) {
        return new Email(value);
    }
}
