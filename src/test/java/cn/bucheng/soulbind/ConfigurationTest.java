package cn.bucheng.soulbind;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @Test void pricesAllowZeroAndIndependentDecimalAmounts() {
        assertEquals(0, SoulBindPlugin.validatePrice(0));
        assertEquals(1250.5, SoulBindPlugin.validatePrice(1250.5));
    }
    @Test void malformedOrUnsafePricesFailValidation() {
        for (Object invalid : new Object[] {"1000", -1, Double.NaN, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SoulBindPlugin.validatePrice(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> SoulBindPlugin.validatePrice(null));
    }
    @Test void languageSubstitutionKeepsPlayerNamesLiteral() {
        assertEquals("§d主人：A&B", Messages.format("&d主人：{owner}", Map.of("owner", "A&B")));
        assertEquals("1000 / 玩家", Messages.format("{price} / {owner}", Map.of("price", "1000", "owner", "玩家")));
    }
}
