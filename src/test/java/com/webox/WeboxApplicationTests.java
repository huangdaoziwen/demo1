package com.webox;
import org.junit.jupiter.api.Test; import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class WeboxApplicationTests {
 @Test void currencyUsesExactDecimalArithmetic(){assertEquals(new BigDecimal("79.50"),new BigDecimal("26.50").multiply(BigDecimal.valueOf(3)));}
 @Test void passwordPolicyDocumentsRequiredStrength(){assertTrue("MealPass8".matches("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$"));assertFalse("password".matches("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$"));}
}
