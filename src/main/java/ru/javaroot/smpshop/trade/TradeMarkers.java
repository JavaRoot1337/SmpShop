package ru.javaroot.smpshop.trade;

import java.util.UUID;

final class TradeMarkers {
    static final String PENDING_PREFIX = "pending:";
    static final String TRANSACTION_PREFIX = "transaction:";
    static final String RECOVERY_PREFIX = "recovery:";

    private TradeMarkers() {
    }

    static String pending(UUID txId) {
        return PENDING_PREFIX + txId;
    }

    static String transaction(String txId) {
        return TRANSACTION_PREFIX + txId;
    }

    static String recovery(long recId) {
        return RECOVERY_PREFIX + recId;
    }
}
