package ru.javaroot.list.trade;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.CurrencyType;
import ru.javaroot.api.event.AdjustmentType;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.currency.Currency;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class TradePaymentSvc {
    void movePaymentToPlayer(InventoryClickEvent event, Player player) {
        ItemStack current = event.getCurrentItem();
        if (!TradeItem.canFit(player.getInventory(), current)) {
            return;
        }
        player.getInventory().addItem(current.clone());
        event.getView().getTopInventory().setItem(event.getRawSlot(), null);
    }

    void movePaymentItem(InventoryClickEvent event, TradeSession session) {
        ItemStack current = event.getCurrentItem();
        Currency currency = TradeItem.isEmpty(current) ? null : Currency.from(current.getType());
        if (currency == null || event.getClickedInventory() == null) {
            return;
        }
        int amount = current.getAmount();
        for (int slot : session.paymentSlots) {
            ItemStack target = session.inventory.getItem(slot);
            if (!canAcceptPayment(target, currency)) {
                continue;
            }
            boolean empty = TradeItem.isEmpty(target);
            int room = empty ? current.getMaxStackSize() : target.getMaxStackSize() - target.getAmount();
            int moved = Math.min(amount, room);
            if (moved < 1) {
                continue;
            }
            if (empty) {
                ItemStack placed = current.clone();
                placed.setAmount(moved);
                session.inventory.setItem(slot, placed);
            } else {
                target.setAmount(target.getAmount() + moved);
            }
            amount -= moved;
            if (amount == 0) {
                break;
            }
        }
        if (amount == current.getAmount()) {
            return;
        }
        Inventory source = event.getClickedInventory();
        if (amount == 0) {
            source.setItem(event.getSlot(), null);
        } else {
            current.setAmount(amount);
            source.setItem(event.getSlot(), current);
        }
    }

    boolean isPaymentPickup(InventoryClickEvent event, TradeSession session) {
        return session.paymentSlots.contains(event.getRawSlot()) && isCurrencyPickup(event);
    }

    boolean isPaymentShiftPickup(InventoryClickEvent event, TradeSession session) {
        return session.paymentSlots.contains(event.getRawSlot())
                && (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && !TradeItem.isEmpty(event.getCurrentItem())
                && Currency.from(event.getCurrentItem().getType()) != null;
    }

    boolean isCurrencyPickup(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || (event.getAction() != InventoryAction.PICKUP_ALL && event.getAction() != InventoryAction.PICKUP_HALF)
                || !TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        ItemStack current = event.getCurrentItem();
        return !TradeItem.isEmpty(current) && Currency.from(current.getType()) != null;
    }

    boolean isCurrencyPlacement(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getCursor().getType());
        return currency != null && canAcceptPayment(event.getCurrentItem(), currency);
    }

    boolean isPaymentClick(InventoryClickEvent event, TradeSession session) {
        int slot = event.getRawSlot();
        if (!session.paymentSlots.contains(slot)
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getCursor().getType());
        return currency != null && canAcceptPayment(event.getCurrentItem(), currency);
    }

    boolean isPaymentDrag(InventoryDragEvent event, TradeSession session) {
        if (event.getRawSlots().isEmpty() || TradeItem.isEmpty(event.getOldCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getOldCursor().getType());
        if (currency == null) {
            return false;
        }
        int topSize = session.inventory.getSize();
        boolean topPayment = event.getRawSlots().stream()
                .allMatch(slot -> slot < topSize && session.paymentSlots.contains(slot)
                        && canAcceptPayment(session.inventory.getItem(slot), currency));
        boolean bottomInventory = event.getRawSlots().stream()
                .allMatch(slot -> slot >= topSize
                        && canAcceptPayment(event.getView().getBottomInventory().getItem(slot - topSize), currency));
        return topPayment || bottomInventory;
    }

    long paymentTotal(TradeSession session) {
        long total = 0;
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            if (item != null && Currency.from(item.getType()) != null) {
                total += item.getAmount();
            }
        }
        return total;
    }

    EnumMap<Currency, Long> paymentPlan(TradeSession session, long price) {
        EnumMap<Currency, Long> result = new EnumMap<>(Currency.class);
        long remaining = price;
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency == null) {
                continue;
            }
            long used = Math.min(item.getAmount(), remaining);
            result.merge(currency, used, Long::sum);
            remaining -= used;
            if (remaining == 0) {
                break;
            }
        }
        return result;
    }

    Payment adjustedPayment(TradeSession session, EnumMap<Currency, Long> base,
                                   List<PurchaseAdjustment> adjustments) {
        EnumMap<Currency, Long> buyer = new EnumMap<>(base);
        EnumMap<Currency, Long> seller = new EnumMap<>(base);
        try {
            for (PurchaseAdjustment adjustment : adjustments) {
                for (Currency currency : Currency.values()) {
                    long value = adjustment.amounts().amount(currency == Currency.DIAMOND_ORE
                            ? CurrencyType.DIAMOND_ORE : CurrencyType.DEEPSLATE_DIAMOND_ORE);
                    if (value == 0) {
                        continue;
                    }
                    if (adjustment.type() == AdjustmentType.BUYER_SURCHARGE) {
                        buyer.put(currency, Math.addExact(buyer.getOrDefault(currency, 0L), value));
                    } else {
                        long payout = seller.getOrDefault(currency, 0L);
                        if (payout < value) {
                            return null;
                        }
                        seller.put(currency, payout - value);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            return null;
        }
        return hasPayment(session, buyer) ? new Payment(buyer, seller) : null;
    }

    boolean hasPayment(TradeSession session, Map<Currency, Long> required) {
        EnumMap<Currency, Long> available = new EnumMap<>(Currency.class);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency != null) {
                available.merge(currency, (long) item.getAmount(), Long::sum);
            }
        }
        return required.entrySet().stream()
                .allMatch(entry -> available.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    void takePayment(TradeSession session, Map<Currency, Long> required) {
        EnumMap<Currency, Long> remaining = new EnumMap<>(Currency.class);
        remaining.putAll(required);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency == null) {
                continue;
            }
            long needed = remaining.getOrDefault(currency, 0L);
            int used = (int) Math.min(item.getAmount(), needed);
            if (used == 0) {
                continue;
            }
            item.setAmount(item.getAmount() - used);
            if (item.getAmount() == 0) {
                session.inventory.setItem(slot, null);
            }
            remaining.put(currency, needed - used);
        }
    }

    CurrencyAmounts amounts(Map<Currency, Long> values) {
        return new CurrencyAmounts(values.getOrDefault(Currency.DIAMOND_ORE, 0L),
                values.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L));
    }

    void givePayment(Player player, Map<Currency, Long> payment) {
        for (Map.Entry<Currency, Long> entry : payment.entrySet()) {
            long left = entry.getValue();
            while (left > 0) {
                int stack = (int) Math.min(64, left);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                        new ItemStack(entry.getKey().material(), stack));
                long extra = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
                if (extra > 0) {
                    player.getWorld().dropItemNaturally(player.getLocation(),
                            new ItemStack(entry.getKey().material(), (int) extra));
                }
                left -= stack;
            }
        }
    }

    boolean canAcceptPayment(ItemStack current, Currency currency) {
        return TradeItem.isEmpty(current)
                || (current.getType() == currency.material() && current.getAmount() < current.getMaxStackSize());
    }
    record Payment(EnumMap<Currency, Long> buyer, EnumMap<Currency, Long> seller) {
    }
}
