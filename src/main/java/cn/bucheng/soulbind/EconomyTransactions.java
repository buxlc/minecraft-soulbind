package cn.bucheng.soulbind;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import java.util.function.Supplier;

/** A receipt retains the actual provider used to debit, including during a service reload. */
public final class EconomyTransactions {
    private final Supplier<Economy> provider;

    public EconomyTransactions(Supplier<Economy> provider) { this.provider = provider; }

    public Receipt charge(Player player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid price");
        if (amount == 0) return new Receipt(null, player, 0);
        Economy economy = provider.get();
        if (economy == null) throw new PaymentFailure("messages.no-economy", "");
        if (!economy.has(player, amount)) throw new PaymentFailure("messages.insufficient-funds", "");
        EconomyResponse result = economy.withdrawPlayer(player, amount);
        if (result == null || !result.transactionSuccess()) {
            throw new PaymentFailure("messages.payment-failed", result == null ? "No response" : String.valueOf(result.errorMessage));
        }
        return new Receipt(economy, player, amount);
    }

    public static final class Receipt {
        private final Economy economy;
        private final Player player;
        private final double amount;
        private boolean refunded;
        Receipt(Economy economy, Player player, double amount) { this.economy = economy; this.player = player; this.amount = amount; }
        public boolean refund() {
            if (refunded || amount == 0) return true;
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (response == null || !response.transactionSuccess()) return false;
            refunded = true;
            return true;
        }
    }

    public static final class PaymentFailure extends RuntimeException {
        private final String key;
        PaymentFailure(String key, String detail) { super(detail); this.key = key; }
        public String key() { return key; }
    }
}
