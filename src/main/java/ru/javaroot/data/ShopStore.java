package ru.javaroot.data;

import ru.javaroot.currency.Currency;
import ru.javaroot.shop.Shop;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ShopStore {
    private final ShopRepo shops;
    private final ShopTransactionRepo transactions;

    public ShopStore(Sqlite db) {
        shops = new ShopRepo(db);
        transactions = new ShopTransactionRepo(db);
    }

    public CompletableFuture<List<Shop>> loadAll() {
        return shops.loadAll();
    }

    public CompletableFuture<Long> insert(Shop shop) {
        return shops.insert(shop);
    }

    public CompletableFuture<Void> delete(long id) {
        return shops.delete(id);
    }

    public CompletableFuture<ApiShopResult> apiCreate(UUID operationId, String source,
                                                       String requestHash, Shop shop) {
        return shops.apiCreate(operationId, source, requestHash, shop);
    }

    public CompletableFuture<ApiShopResult> apiResult(UUID operationId, String source,
                                                       String requestHash) {
        return shops.apiResult(operationId, source, requestHash);
    }

    public CompletableFuture<ApiShopResult> apiStatus(UUID operationId, String source,
                                                       String requestHash, String type,
                                                       String status, Long shopId) {
        return shops.apiStatus(operationId, source, requestHash, type, status, shopId);
    }

    public CompletableFuture<ApiShopResult> apiUpdatePrice(UUID operationId, String source,
                                                            String requestHash, long shopId, int price) {
        return shops.apiUpdatePrice(operationId, source, requestHash, shopId, price);
    }

    public CompletableFuture<ApiShopResult> apiDelete(UUID operationId, String source,
                                                       String requestHash, long shopId) {
        return shops.apiDelete(operationId, source, requestHash, shopId);
    }

    public CompletableFuture<Void> prepareSale(String transactionId, Shop shop, UUID buyerId,
                                                int itemSlot, int itemAmount, long price,
                                                Map<Currency, Long> payment, Map<Currency, Long> sellerPayout,
                                                byte[] itemSnapshot, String adjustments) {
        return transactions.prepareSale(transactionId, shop, buyerId, itemSlot, itemAmount, price,
                payment, sellerPayout, itemSnapshot, adjustments);
    }

    public CompletableFuture<Boolean> completeSale(String transactionId, Shop shop, int items,
                                                    Map<Currency, Long> sellerPayout) {
        return transactions.completeSale(transactionId, shop, items, sellerPayout);
    }

    public CompletableFuture<Boolean> delivered(String transactionId, UUID buyerId) {
        return transactions.delivered(transactionId, buyerId);
    }

    public CompletableFuture<Boolean> rollback(String transactionId, UUID buyerId, boolean recoverItem) {
        return transactions.rollback(transactionId, buyerId, recoverItem);
    }

    public CompletableFuture<List<PendingDelivery>> pendingDeliveries(UUID buyerId) {
        return transactions.pendingDeliveries(buyerId);
    }

    public CompletableFuture<List<String>> rolledBackTransactions(UUID buyerId) {
        return transactions.rolledBackTransactions(buyerId);
    }

    public CompletableFuture<List<PreparedTransaction>> preparedTransactions() {
        return transactions.preparedTransactions();
    }

    public CompletableFuture<Boolean> recoverRollback(PreparedTransaction transaction, boolean recoverItem) {
        return transactions.recoverRollback(transaction, recoverItem);
    }

    public CompletableFuture<List<RecoveryItem>> recoveryItems(UUID playerId) {
        return transactions.recoveryItems(playerId);
    }

    public CompletableFuture<Boolean> recoveryDelivered(long id, UUID playerId) {
        return transactions.recoveryDelivered(id, playerId);
    }

    public record ApiShopResult(String status, Shop shop, boolean applied) {
    }

    public record PendingDelivery(String transactionId, long shopId, byte[] itemSnapshot) {
        public PendingDelivery {
            itemSnapshot = itemSnapshot.clone();
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    public record PreparedTransaction(String transactionId, long shopId, UUID buyerId, UUID sellerId,
                                      int itemSlot, byte[] itemSnapshot, Map<Currency, Long> payment) {
        public PreparedTransaction {
            itemSnapshot = itemSnapshot.clone();
            payment = Map.copyOf(payment);
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    public record RecoveryItem(long id, byte[] itemSnapshot) {
        public RecoveryItem {
            itemSnapshot = itemSnapshot.clone();
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }
}
