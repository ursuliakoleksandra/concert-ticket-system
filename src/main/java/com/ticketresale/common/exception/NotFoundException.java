package com.ticketresale.common.exception;

/** Ресурс не знайдено → HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Long id) {
        super(resource + " з id=" + id + " не знайдено");
    }
}
