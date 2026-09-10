package com.mercury.execution;

import com.mercury.core.MercuryException;
import com.mercury.core.id.CounterpartyId;
import com.mercury.trade.Counterparty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a counterparty id to the counterparty it names - the same problem
 * {@link com.mercury.portfolio.InstrumentCatalog} solves for instruments, for the same reason:
 * {@link com.mercury.execution.OtcInstruction} carries a bare {@code CounterpartyId}, and
 * checking a proposed trade's exposure against a {@code CreditLimit} needs the full
 * {@link Counterparty}.
 *
 * <p>Lives here, in {@code execution}, rather than in {@code trade} where {@code Counterparty}
 * itself is defined - the same placement logic as {@code InstrumentCatalog}, which lives in
 * {@code portfolio} rather than {@code instrument}: a directory belongs with what resolves
 * through it, not with what it resolves.
 *
 * <p>Immutable and thread-safe.
 */
public final class CounterpartyDirectory {

    private final Map<CounterpartyId, Counterparty> counterparties;

    private CounterpartyDirectory(Map<CounterpartyId, Counterparty> counterparties) {
        this.counterparties = counterparties;
    }

    public static CounterpartyDirectory of(Counterparty... counterparties) {
        return of(List.of(counterparties));
    }

    public static CounterpartyDirectory of(List<Counterparty> counterparties) {
        Map<CounterpartyId, Counterparty> byId = new LinkedHashMap<>();
        for (Counterparty counterparty : counterparties) {
            Objects.requireNonNull(counterparty, "counterparty");
            Counterparty existing = byId.putIfAbsent(counterparty.id(), counterparty);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Two counterparties share the id " + counterparty.id() + ": " + existing.name()
                                + " and " + counterparty.name() + ". Ids identify counterparties, so a "
                                + "duplicate means one of them would silently shadow the other.");
            }
        }
        return new CounterpartyDirectory(Map.copyOf(byId));
    }

    /**
     * @throws UnknownCounterpartyException if nothing is registered under {@code id}
     */
    public Counterparty require(CounterpartyId id) {
        Objects.requireNonNull(id, "id");
        Counterparty counterparty = counterparties.get(id);
        if (counterparty == null) {
            throw new UnknownCounterpartyException(id, counterparties.keySet());
        }
        return counterparty;
    }

    @Override
    public String toString() {
        return "CounterpartyDirectory(" + counterparties.size() + " counterparties)";
    }

    /** Raised when a trade names a counterparty the directory does not hold. */
    public static final class UnknownCounterpartyException extends MercuryException {
        UnknownCounterpartyException(CounterpartyId id, java.util.Set<CounterpartyId> known) {
            super("No counterparty registered as " + id + ". Known: "
                    + known.stream().map(CounterpartyId::value).sorted().toList()
                    + ". A trade against an unknown counterparty has no credit limit to check it "
                    + "against.");
        }
    }
}
