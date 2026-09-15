package ru.javaroot.list.trade;

import java.util.UUID;

final class TradeMarkers {
    static final String PENDING_PREFIX = "pending:";
    static final String TRANSACTION_PREFIX = "transaction:";
    static final String RECOVERY_PREFIX = "recovery:";

    private TradeMarkers() {
    }

    static String pending(UUID transactionId) {
        return PENDING_PREFIX + transactionId;
    }

    static String transaction(String transactionId) {
        return TRANSACTION_PREFIX + transactionId;
    }

    static String recovery(long recoveryId) {
        return RECOVERY_PREFIX + recoveryId;
    }
}
