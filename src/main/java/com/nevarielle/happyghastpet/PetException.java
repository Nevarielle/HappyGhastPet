package com.nevarielle.happyghastpet;

/**
 * A user-facing, recoverable error. Carries a messages.yml key + placeholder pairs
 * so call sites can render it through {@link Messages} instead of leaking raw text.
 */
public final class PetException extends RuntimeException {
    private final transient Object[] placeholders;

    public PetException(String messageKey, Object... placeholders) {
        super(messageKey);
        this.placeholders = placeholders;
    }

    public String key() {
        return getMessage();
    }

    public Object[] placeholders() {
        return placeholders == null ? new Object[0] : placeholders;
    }
}
