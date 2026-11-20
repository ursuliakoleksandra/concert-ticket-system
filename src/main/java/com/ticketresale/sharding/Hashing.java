package com.ticketresale.sharding;

import java.nio.charset.StandardCharsets;

/** Хеш-функції для маршрутизації: детерміновані, однакові на всіх вузлах API. */
final class Hashing {

    private Hashing() {
    }

    /**
     * Фіналізатор MurmurHash3 (fmix64): кожен біт ключа впливає на всі біти результату.
     * Тому послідовні чи «структуровані» ключі (лише парні, кратні N) розподіляються рівномірно.
     */
    static long mix64(long key) {
        long k = key;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** FNV-1a 64 для рядків (імена віртуальних вузлів на кільці). */
    static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }
}
