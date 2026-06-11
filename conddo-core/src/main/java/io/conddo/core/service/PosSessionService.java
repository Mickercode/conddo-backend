package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PosPayment;
import io.conddo.core.domain.PosSale;
import io.conddo.core.domain.PosSession;
import io.conddo.core.repository.PosPaymentRepository;
import io.conddo.core.repository.PosSaleRepository;
import io.conddo.core.repository.PosSessionRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cashier shift lifecycle (POS Phase 1). The OPEN→CLOSED partial
 * unique index in V47 enforces "one OPEN session per cashier" — the
 * service catches the duplicate at the JPA layer and returns it as a
 * domain conflict, not a 500.
 */
@Service
public class PosSessionService {

    private final PosSessionRepository sessionRepository;
    private final PosSaleRepository saleRepository;
    private final PosPaymentRepository paymentRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PosSessionService(PosSessionRepository sessionRepository,
                             PosSaleRepository saleRepository,
                             PosPaymentRepository paymentRepository,
                             TenantSession tenantSession,
                             Clock clock) {
        this.sessionRepository = sessionRepository;
        this.saleRepository = saleRepository;
        this.paymentRepository = paymentRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional
    public PosSession open(UUID cashierId, BigDecimal openingFloat, String notes) {
        tenantSession.bind();
        sessionRepository.findFirstByCashierIdAndStatus(cashierId, PosSession.STATUS_OPEN)
                .ifPresent(s -> {
                    throw new SessionAlreadyOpenException(s.getId());
                });
        return sessionRepository.save(new PosSession(TenantContext.require(), cashierId,
                openingFloat, notes, OffsetDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public Optional<View> findCurrent(UUID cashierId) {
        tenantSession.bind();
        return sessionRepository.findFirstByCashierIdAndStatus(cashierId, PosSession.STATUS_OPEN)
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public View get(UUID sessionId) {
        tenantSession.bind();
        return view(sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found")));
    }

    @Transactional
    public View close(UUID sessionId, BigDecimal countedCash, String closingNotes) {
        tenantSession.bind();
        PosSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!PosSession.STATUS_OPEN.equals(session.getStatus())) {
            throw new IllegalStateException("Session already closed");
        }
        long openSales = saleRepository.countBySessionIdAndStatus(sessionId, PosSale.STATUS_OPEN);
        if (openSales > 0) {
            throw new SessionHasOpenSalesException(openSales);
        }
        Summary summary = summarize(sessionId);
        BigDecimal expectedCash = session.getOpeningFloat().add(summary.totalCash);
        session.close(expectedCash, countedCash, closingNotes, OffsetDateTime.now(clock));
        sessionRepository.save(session);
        return new View(session, summary);
    }

    private View view(PosSession session) {
        return new View(session, summarize(session.getId()));
    }

    private Summary summarize(UUID sessionId) {
        List<PosSale> sales = saleRepository.findBySessionId(sessionId);
        BigDecimal totalSales = BigDecimal.ZERO;
        long completedCount = 0;
        BigDecimal totalCash = BigDecimal.ZERO;
        BigDecimal totalTransfer = BigDecimal.ZERO;
        for (PosSale sale : sales) {
            if (!PosSale.STATUS_COMPLETED.equals(sale.getStatus())) {
                continue;
            }
            completedCount++;
            totalSales = totalSales.add(sale.getTotal());
            for (PosPayment payment : paymentRepository.findBySaleIdOrderByPaidAtAsc(sale.getId())) {
                if (PosPayment.METHOD_CASH.equals(payment.getMethod())) {
                    totalCash = totalCash.add(payment.getAmount());
                } else if (PosPayment.METHOD_TRANSFER.equals(payment.getMethod())) {
                    totalTransfer = totalTransfer.add(payment.getAmount());
                }
            }
        }
        return new Summary(completedCount, totalSales, totalCash, totalTransfer);
    }

    public record View(PosSession session, Summary summary) {
    }

    public record Summary(long salesCount, BigDecimal totalSales,
                          BigDecimal totalCash, BigDecimal totalTransfer) {
    }

    public static class SessionAlreadyOpenException extends RuntimeException {
        private final UUID existingSessionId;

        public SessionAlreadyOpenException(UUID existingSessionId) {
            super("Cashier already has an OPEN session: " + existingSessionId);
            this.existingSessionId = existingSessionId;
        }

        public UUID getExistingSessionId() {
            return existingSessionId;
        }
    }

    public static class SessionHasOpenSalesException extends RuntimeException {
        public SessionHasOpenSalesException(long count) {
            super("Session has " + count + " open sale(s); complete or void them first");
        }
    }
}
