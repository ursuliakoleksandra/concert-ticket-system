package com.ticketresale.cache;

/** Джерело даних відповіді — повертається клієнту в заголовку X-Cache. */
public enum CacheStatus {
    /** Дані з Redis, БД не використовувалась. */
    HIT,
    /** Даних у кеші не було: прочитано з БД і збережено в кеш. */
    MISS,
    /** Redis недоступний: дані прочитано з БД напряму (Graceful Degradation). */
    BYPASS,
    /** Кеш вимкнено налаштуванням resale.cache.enabled=false. */
    DISABLED
}
