package cn.bucheng.soulbind;

import net.milkbowl.vault.economy.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyTransactionsTest {
    private final Player player = mock(Player.class);
    private final Economy economy = mock(Economy.class);

    @Test void insufficientFundsNeverWithdraws() {
        when(economy.has(player, 1000)).thenReturn(false);
        var failure = assertThrows(EconomyTransactions.PaymentFailure.class, () -> new EconomyTransactions(() -> economy).charge(player, 1000));
        assertEquals("messages.insufficient-funds", failure.key());
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
    }

    @Test void missingEconomyFailsWithoutTouchingItemOperation() {
        var failure = assertThrows(EconomyTransactions.PaymentFailure.class, () -> new EconomyTransactions(() -> null).charge(player, 1000));
        assertEquals("messages.no-economy", failure.key());
    }

    @Test void failedWithdrawalIsReported() {
        when(economy.has(player, 1000)).thenReturn(true);
        when(economy.withdrawPlayer(player, 1000)).thenReturn(response(false));
        assertThrows(EconomyTransactions.PaymentFailure.class, () -> new EconomyTransactions(() -> economy).charge(player, 1000));
        verify(economy, never()).depositPlayer(any(Player.class), anyDouble());
    }

    @Test void receiptRefundsOnlyOnceToTheOriginalProvider() {
        when(economy.has(player, 1000)).thenReturn(true);
        when(economy.withdrawPlayer(player, 1000)).thenReturn(response(true));
        when(economy.depositPlayer(player, 1000)).thenReturn(response(true));
        var receipt = new EconomyTransactions(() -> economy).charge(player, 1000);
        assertTrue(receipt.refund()); assertTrue(receipt.refund());
        verify(economy, times(1)).depositPlayer(player, 1000);
    }

    @Test void failedRefundRemainsRetryable() {
        when(economy.has(player, 25)).thenReturn(true);
        when(economy.withdrawPlayer(player, 25)).thenReturn(response(true));
        when(economy.depositPlayer(player, 25)).thenReturn(response(false), response(true));
        var receipt = new EconomyTransactions(() -> economy).charge(player, 25);
        assertFalse(receipt.refund()); assertTrue(receipt.refund());
    }

    @Test void freeOperationsDoNotNeedEconomy() {
        assertTrue(new EconomyTransactions(() -> null).charge(player, 0).refund());
    }

    @Test void nonFiniteOrNegativePricesAreRejected() {
        var transactions = new EconomyTransactions(() -> economy);
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> transactions.charge(player, invalid));
        }
        verifyNoInteractions(economy);
    }

    private EconomyResponse response(boolean success) {
        return new EconomyResponse(1000, 2000, success ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, "test failure");
    }
}
