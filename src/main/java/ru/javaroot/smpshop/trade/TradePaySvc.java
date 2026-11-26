package ru.javaroot.smpshop.trade;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.CurrencyType;
import ru.javaroot.api.event.AdjustmentType;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.smpshop.shop.CurType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class TradePaySvc {
    void movePaymentToPlayer(InventoryClickEvent event, Player p) {
        ItemStack cur = event.getCurrentItem();
        if (!TradeItem.canFit(p.getInventory(), cur)) {
            return;
        }
        p.getInventory().addItem(cur.clone());
        event.getView().getTopInventory().setItem(event.getRawSlot(), null);
    }

    void movePaymentItem(InventoryClickEvent event, TradeSess sess) {
        ItemStack cur = event.getCurrentItem();
        CurType type = TradeItem.isEmpty(cur) ? null : CurType.from(cur.getType());
        if (type == null || event.getClickedInventory() == null) {
            return;
        }
        Inventory top = sess.getInventory();
        int amount = cur.getAmount();
        for (int slot : sess.paymentSlots) {
            ItemStack target = top.getItem(slot);
            if (!canAccept(target, type)) {
                continue;
            }
            boolean empty = TradeItem.isEmpty(target);
            int room = empty ? cur.getMaxStackSize() : target.getMaxStackSize() - target.getAmount();
            int moved = Math.min(amount, room);
            if (moved < 1) {
                continue;
            }
            if (empty) {
                ItemStack placed = cur.clone();
                placed.setAmount(moved);
                top.setItem(slot, placed);
            } else {
                target.setAmount(target.getAmount() + moved);
            }
            amount -= moved;
            if (amount == 0) {
                break;
            }
        }
        if (amount == cur.getAmount()) {
            return;
        }
        Inventory src = event.getClickedInventory();
        if (amount == 0) {
            src.setItem(event.getSlot(), null);
        } else {
            cur.setAmount(amount);
            src.setItem(event.getSlot(), cur);
        }
    }

    boolean isPaymentPickup(InventoryClickEvent event, TradeSess sess) {
        return sess.paymentSlots.contains(event.getRawSlot()) && isCurrencyPickup(event);
    }

    boolean isPaymentShiftPickup(InventoryClickEvent event, TradeSess sess) {
        return sess.paymentSlots.contains(event.getRawSlot())
                && (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && !TradeItem.isEmpty(event.getCurrentItem())
                && CurType.from(event.getCurrentItem().getType()) != null;
    }

    boolean isCurrencyPickup(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || (event.getAction() != InventoryAction.PICKUP_ALL && event.getAction() != InventoryAction.PICKUP_HALF)
                || !TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        ItemStack cur = event.getCurrentItem();
        return !TradeItem.isEmpty(cur) && CurType.from(cur.getType()) != null;
    }

    boolean isCurrencyPlacement(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        CurType type = CurType.from(event.getCursor().getType());
        return type != null && canAccept(event.getCurrentItem(), type);
    }

    boolean isPaymentClick(InventoryClickEvent event, TradeSess sess) {
        if (!sess.paymentSlots.contains(event.getRawSlot())
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || TradeItem.isEmpty(event.getCursor())) {
            return false;
        }
        CurType type = CurType.from(event.getCursor().getType());
        return type != null && canAccept(event.getCurrentItem(), type);
    }

    boolean isPaymentDrag(InventoryDragEvent event, TradeSess sess) {
        if (event.getRawSlots().isEmpty() || TradeItem.isEmpty(event.getOldCursor())) {
            return false;
        }
        CurType type = CurType.from(event.getOldCursor().getType());
        if (type == null) {
            return false;
        }
        Inventory top = sess.getInventory();
        int topSize = top.getSize();
        boolean topPay = event.getRawSlots().stream()
                .allMatch(slot -> slot < topSize && sess.paymentSlots.contains(slot)
                        && canAccept(top.getItem(slot), type));
        boolean botInv = event.getRawSlots().stream()
                .allMatch(slot -> slot >= topSize
                        && canAccept(event.getView().getBottomInventory().getItem(slot - topSize), type));
        return topPay || botInv;
    }

    long paymentTotal(TradeSess sess) {
        long total = 0;
        Inventory top = sess.getInventory();
        for (int slot : sess.paymentSlots) {
            ItemStack item = top.getItem(slot);
            if (item != null && CurType.from(item.getType()) != null) {
                total += item.getAmount();
            }
        }
        return total;
    }

    EnumMap<CurType, Long> paymentPlan(TradeSess sess, long price) {
        EnumMap<CurType, Long> res = new EnumMap<>(CurType.class);
        long rem = price;
        Inventory top = sess.getInventory();
        for (int slot : sess.paymentSlots) {
            ItemStack item = top.getItem(slot);
            CurType type = item == null ? null : CurType.from(item.getType());
            if (type == null) {
                continue;
            }
            long used = Math.min(item.getAmount(), rem);
            res.merge(type, used, Long::sum);
            rem -= used;
            if (rem == 0) {
                break;
            }
        }
        return res;
    }

    Payment adjustedPayment(TradeSess sess, EnumMap<CurType, Long> base, List<PurchaseAdjustment> adjustments) {
        EnumMap<CurType, Long> buyer = new EnumMap<>(base);
        EnumMap<CurType, Long> seller = new EnumMap<>(base);
        try {
            for (PurchaseAdjustment adj : adjustments) {
                for (CurType cur : CurType.values()) {
                    long val = adj.amounts().amount(cur == CurType.DIAMOND_ORE
                            ? CurrencyType.DIAMOND_ORE : CurrencyType.DEEPSLATE_DIAMOND_ORE);
                    if (val == 0) {
                        continue;
                    }
                    if (adj.type() == AdjustmentType.BUYER_SURCHARGE) {
                        buyer.put(cur, Math.addExact(buyer.getOrDefault(cur, 0L), val));
                    } else {
                        long payout = seller.getOrDefault(cur, 0L);
                        if (payout < val) {
                            return null;
                        }
                        seller.put(cur, payout - val);
                    }
                }
            }
        } catch (ArithmeticException ex) {
            return null;
        }
        return hasPayment(sess, buyer) ? new Payment(buyer, seller) : null;
    }

    boolean hasPayment(TradeSess sess, Map<CurType, Long> req) {
        EnumMap<CurType, Long> avail = new EnumMap<>(CurType.class);
        Inventory top = sess.getInventory();
        for (int slot : sess.paymentSlots) {
            ItemStack item = top.getItem(slot);
            CurType type = item == null ? null : CurType.from(item.getType());
            if (type != null) {
                avail.merge(type, (long) item.getAmount(), Long::sum);
            }
        }
        return req.entrySet().stream()
                .allMatch(e -> avail.getOrDefault(e.getKey(), 0L) >= e.getValue());
    }

    void takePayment(TradeSess sess, Map<CurType, Long> req) {
        EnumMap<CurType, Long> rem = new EnumMap<>(CurType.class);
        rem.putAll(req);
        Inventory top = sess.getInventory();
        for (int slot : sess.paymentSlots) {
            ItemStack item = top.getItem(slot);
            CurType type = item == null ? null : CurType.from(item.getType());
            if (type == null) {
                continue;
            }
            long need = rem.getOrDefault(type, 0L);
            int used = (int) Math.min(item.getAmount(), need);
            if (used == 0) {
                continue;
            }
            item.setAmount(item.getAmount() - used);
            if (item.getAmount() == 0) {
                top.setItem(slot, null);
            }
            rem.put(type, need - used);
        }
    }

    CurrencyAmounts amounts(Map<CurType, Long> vals) {
        return new CurrencyAmounts(
                vals.getOrDefault(CurType.DIAMOND_ORE, 0L),
                vals.getOrDefault(CurType.DEEPSLATE_DIAMOND_ORE, 0L)
        );
    }

    void givePayment(Player p, Map<CurType, Long> pay) {
        for (Map.Entry<CurType, Long> entry : pay.entrySet()) {
            long left = entry.getValue();
            while (left > 0) {
                int stack = (int) Math.min(64, left);
                Map<Integer, ItemStack> overflow = p.getInventory().addItem(new ItemStack(entry.getKey().mat(), stack));
                long extra = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
                if (extra > 0) {
                    p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(entry.getKey().mat(), (int) extra));
                }
                left -= stack;
            }
        }
    }

    private static boolean canAccept(ItemStack cur, CurType type) {
        return TradeItem.isEmpty(cur) || (cur.getType() == type.mat() && cur.getAmount() < cur.getMaxStackSize());
    }

    record Payment(EnumMap<CurType, Long> buyer, EnumMap<CurType, Long> seller) {
    }
}
