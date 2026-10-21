package com.ticketresale.common.exception;

/** Порушення бізнес-правила (ліміт ціни, ліміт покупок тощо) → HTTP 422. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
