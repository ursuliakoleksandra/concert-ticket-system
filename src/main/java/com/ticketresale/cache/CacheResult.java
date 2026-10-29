package com.ticketresale.cache;

/** Результат читання разом із джерелом даних (для заголовка X-Cache). */
public record CacheResult<T>(T value, CacheStatus status) {
}
