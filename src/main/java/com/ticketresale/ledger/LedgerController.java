package com.ticketresale.ledger;

import java.net.URI;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.ledger.LedgerDtos.Distribution;
import com.ticketresale.ledger.LedgerDtos.EntryRequest;
import com.ticketresale.ledger.LedgerDtos.EntryResponse;
import com.ticketresale.ledger.LedgerDtos.SalesReport;
import com.ticketresale.ledger.LedgerDtos.UserLedger;
import com.ticketresale.sharding.ShardRouter;

import jakarta.validation.Valid;

/**
 * API журналу операцій (ЛР7). Заголовок X-Shard показує, які шарди обслужили запит.
 */
@RestController
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** Shard-Local запис (використовується генератором навантаження). */
    @PostMapping("/api/ledger/entries")
    public ResponseEntity<EntryResponse> create(@Valid @RequestBody EntryRequest request) {
        EntryResponse body = ledger.record(request.userId(), request.eventId(), request.type(), request.amount());
        return ResponseEntity.created(URI.create("/api/users/" + body.userId() + "/ledger"))
                .header(ShardRouter.HEADER, ledger.shardOf(request.userId()))
                .body(body);
    }

    /** Shard-Local читання: історія операцій користувача. */
    @GetMapping("/api/users/{userId}/ledger")
    public ResponseEntity<UserLedger> history(@PathVariable long userId,
                                              @RequestParam(defaultValue = "20") int limit) {
        UserLedger body = ledger.history(userId, Math.max(1, Math.min(limit, 200)));
        return ResponseEntity.ok().header(ShardRouter.HEADER, body.shard()).body(body);
    }

    /** Cross-Shard читання (Scatter-Gather): продажі події з усіх шардів. */
    @GetMapping("/api/reports/events/{eventId}/sales")
    public ResponseEntity<SalesReport> sales(@PathVariable long eventId,
                                             @RequestParam(defaultValue = "parallel") String mode) {
        SalesReport body = ledger.salesReport(eventId, !"sequential".equals(mode));
        String shards = body.shards().stream().map(LedgerDtos.ShardPart::shard).collect(Collectors.joining(","));
        return ResponseEntity.ok().header(ShardRouter.HEADER, shards).body(body);
    }

    /** Генерація тестового набору даних і матриця розподілу (лише для лабораторного стенду). */
    @PostMapping("/api/admin/ledger/generate")
    public Distribution generate(@RequestParam(defaultValue = "100000") int count,
                                 @RequestParam(defaultValue = "10000") int users,
                                 @RequestParam(defaultValue = "50") int events,
                                 @RequestParam(defaultValue = "uniform") String pattern) {
        return ledger.generate(count, users, events, pattern);
    }

    @GetMapping("/api/admin/ledger/distribution")
    public Distribution distribution() {
        return ledger.distribution();
    }

    @PostMapping("/api/admin/ledger/reset-counters")
    public ResponseEntity<Void> resetCounters() {
        ledger.resetWriteCounters();
        return ResponseEntity.noContent().build();
    }
}
