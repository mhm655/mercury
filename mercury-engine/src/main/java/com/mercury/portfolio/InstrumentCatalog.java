package com.mercury.portfolio;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.instrument.FinancialInstrument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves an instrument id to the instrument it names.
 *
 * <h2>Why positions hold ids rather than instruments</h2>
 * A {@link Position} references its instrument by identity, not by pointer. Two reasons:
 *
 * <ul>
 *   <li>An instrument's terms can be amended, and a position should follow the amendment
 *       rather than pin a stale copy. Since instruments use entity equality on their id, the
 *       reference stays valid.</li>
 *   <li>It keeps a portfolio a small flat structure rather than the root of an object graph,
 *       which matters when snapshots of it are handed to risk workers.</li>
 * </ul>
 *
 * <p>The cost is that a reference can dangle, so lookup fails loudly rather than returning
 * null - a valuation that silently skipped an unresolvable position would report a total that
 * looks complete and is not.
 *
 * <p>Immutable and thread-safe.
 */
public final class InstrumentCatalog {

    private final Map<InstrumentId, FinancialInstrument> instruments;

    private InstrumentCatalog(Map<InstrumentId, FinancialInstrument> instruments) {
        this.instruments = instruments;
    }

    public static InstrumentCatalog of(FinancialInstrument... instruments) {
        return of(List.of(instruments));
    }

    public static InstrumentCatalog of(List<FinancialInstrument> instruments) {
        Map<InstrumentId, FinancialInstrument> byId = new LinkedHashMap<>();
        for (FinancialInstrument instrument : instruments) {
            Objects.requireNonNull(instrument, "instrument");
            FinancialInstrument existing = byId.putIfAbsent(instrument.id(), instrument);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Two instruments share the id " + instrument.id()
                                + ": " + existing.description() + " and " + instrument.description()
                                + ". Ids identify instruments, so a duplicate means one of them "
                                + "would silently shadow the other.");
            }
        }
        return new InstrumentCatalog(Map.copyOf(byId));
    }

    /**
     * @throws UnknownInstrumentException if nothing is registered under {@code id}
     */
    public FinancialInstrument require(InstrumentId id) {
        Objects.requireNonNull(id, "id");
        FinancialInstrument instrument = instruments.get(id);
        if (instrument == null) {
            throw new UnknownInstrumentException(id, instruments.keySet());
        }
        return instrument;
    }

    @Override
    public String toString() {
        return "InstrumentCatalog(" + instruments.size() + " instruments)";
    }

    /** Raised when a position references an instrument the catalog does not hold. */
    public static final class UnknownInstrumentException extends MercuryException {
        UnknownInstrumentException(InstrumentId id, java.util.Set<InstrumentId> known) {
            super("No instrument registered as " + id + ". Known: "
                    + known.stream().map(InstrumentId::value).sorted().toList()
                    + ". A position referencing an unknown instrument cannot be valued, and "
                    + "skipping it would produce a total that looks complete but is not.");
        }
    }
}
