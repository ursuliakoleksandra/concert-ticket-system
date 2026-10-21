package com.ticketresale.common.exception;

/** Стан ресурсу не дозволяє виконати операцію (напр., оголошення вже продано) → HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
