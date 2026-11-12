package com.athletex.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload.GameState;
import com.athletex.ingestion.EventIngestionService;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.trading.PositionEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementCorrectionTest extends AbstractLiveSlateTest {

    private record Settled(Slate slate, UUID alice, UUID bob, EventEnvelope officialLine) {
    }

    /** Alice holds 600 and Bob 400 shares bought at $10.00; the athlete scores exactly 40 points ($10.00). */
    private Settled settledSlate() {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        UUID bob = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 600);
        ipoService.bid(bob, s.markets.get(0), 1000, 400);
        s.openMarket();
        EventEnvelope line = s.apply(s.line(0, 40, 0, 0, true));
        s.apply(s.clock(GameState.FINAL, 4, 0));
        assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.SETTLED);
        assertThat(s.market(0).getSettlementValueCents()).isEqualTo(1000);
        assertThat(cash(alice)).isEqualTo(SEED_CENTS);
        return new Settled(s, alice, bob, line);
    }

    @Test
    void upwardRevisionPaysHoldersTheDifference() {
        Settled t = settledSlate();
        long poolBefore = poolBalance();

        EventEnvelope revision = t.slate().correct(t.officialLine(), t.slate().line(0, 44, 0, 0, true));

        assertThat(t.slate().market(0).getSettlementValueCents()).isEqualTo(1100);
        assertThat(t.slate().market(0).getFairValueCents()).isEqualTo(1100);
        assertThat(cash(t.alice())).isEqualTo(SEED_CENTS + 600 * 100);
        assertThat(cash(t.bob())).isEqualTo(SEED_CENTS + 400 * 100);
        assertThat(poolBefore - poolBalance()).isEqualTo(1000 * 100);
        assertThat(realized(t.alice(), t.slate())).isEqualTo(600 * 100);

        SettlementCorrectionEntity correction = correctionRepository.findAll().stream()
                .filter(c -> c.getEventId().equals(revision.eventId())).findFirst().orElseThrow();
        assertThat(correction.isApplied()).isTrue();
        assertThat(correction.getOldValueCents()).isEqualTo(1000);
        assertThat(correction.getNewValueCents()).isEqualTo(1100);
        assertThat(correction.getTotalAdjustmentCents()).isEqualTo(100_000);

        // The same revision delivered again is a duplicate event and moves no money.
        assertThat(ingestion.ingest(revision)).isEqualTo(EventIngestionService.Outcome.DUPLICATE);
        assertThat(cash(t.alice())).isEqualTo(SEED_CENTS + 600 * 100);
        assertThat(allBalances()).isZero();
    }

    @Test
    void downwardRevisionClawsBackEvenIntoOverdraft() {
        Settled t = settledSlate();
        // Bob has spent (here: transferred away) everything he was paid.
        long bobCash = cash(t.bob());
        ledgerService.post("drain:" + t.bob(), TransactionType.TRANSFER, List.of(
                new Posting(ledgerService.accountIdOf(t.bob(), AccountType.USER_CASH), -bobCash),
                new Posting(AccountEntity.HOUSE_ACCOUNT_ID, bobCash)));

        t.slate().correct(t.officialLine(), t.slate().line(0, 30, 0, 0, true)); // $10.00 -> $7.50

        assertThat(cash(t.bob())).isEqualTo(-400 * 250L);
        assertThat(cash(t.alice())).isEqualTo(SEED_CENTS - 600 * 250L);
        assertThat(t.slate().market(0).getSettlementValueCents()).isEqualTo(750);
        assertThat(allBalances()).isZero();
    }

    @Test
    void revisionAfterTheWindowIsRecordedButNotPaid() {
        Settled t = settledSlate();
        jdbc.update("update settlement_runs set settled_at = now() - interval '3 days' where slate_id = ?",
                t.slate().slateId);
        long poolBefore = poolBalance();

        EventEnvelope late = t.slate().correct(t.officialLine(), t.slate().line(0, 48, 0, 0, true));

        SettlementCorrectionEntity correction = correctionRepository.findAll().stream()
                .filter(c -> c.getEventId().equals(late.eventId())).findFirst().orElseThrow();
        assertThat(correction.isApplied()).isFalse();
        assertThat(correction.getReason()).contains("window");
        assertThat(t.slate().market(0).getSettlementValueCents()).isEqualTo(1000);
        assertThat(cash(t.alice())).isEqualTo(SEED_CENTS);
        assertThat(poolBalance()).isEqualTo(poolBefore);
    }

    private long realized(UUID user, Slate slate) {
        return positionRepository.findByUserIdAndMarketId(user, slate.markets.get(0))
                .map(PositionEntity::getRealizedPnlCents).orElseThrow();
    }
}
