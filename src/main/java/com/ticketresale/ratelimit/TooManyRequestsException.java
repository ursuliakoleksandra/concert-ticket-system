package com.ticketresale.ratelimit;

/** Перевищено ліміт запитів → HTTP 429 з заголовком Retry-After. */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Забагато запитів. Повторіть через " + retryAfterSeconds + " с");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
