package com.ticketresale.cache;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Форма сторінки, що зберігається в Redis: лише вміст і загальна кількість.
 * Параметри пагінації входять у ключ кешу, тому з них сторінка відновлюється без втрат.
 */
public record CachedPage<T>(List<T> content, long totalElements) {

    public static <T> CachedPage<T> of(Page<T> page) {
        return new CachedPage<>(page.getContent(), page.getTotalElements());
    }

    public Page<T> toPage(Pageable pageable) {
        return new PageImpl<>(content, pageable, totalElements);
    }
}
