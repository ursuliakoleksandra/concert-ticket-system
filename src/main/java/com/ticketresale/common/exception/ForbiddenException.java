package com.ticketresale.common.exception;

/** Користувач не має права виконувати дію над ресурсом → HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
