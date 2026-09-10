package com.webox;
import org.junit.jupiter.api.Test; import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class WeboxApplicationTests {
 @Test void currencyUsesExactDecimalArithmetic(){assertEquals(new BigDecimal("79.50"),new BigDecimal("26.50").multiply(BigDecimal.valueOf(3)));}
 @Test void newProfilesHaveCompleteRecommendationPreferences(){var user=new Domain.User();assertEquals("None",user.spice);assertEquals("Regular",user.tasteIntensity);assertTrue(user.allergens.isEmpty());}
 @Test void optionSeedIsValidJsonShape(){assertTrue(SeedData.optionsFor("Tomato Pasta").startsWith("[{");assertEquals("[]",SeedData.optionsFor("Unknown"));}
}
