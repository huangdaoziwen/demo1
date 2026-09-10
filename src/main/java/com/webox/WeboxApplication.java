package com.webox;
import org.junit.jupiter.api.Test; import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class WeboxApplicationTests {
 @Test void currencyUsesExactDecimalArithmetic(){assertEquals(new BigDecimal("79.50"),new BigDecimal("26.50").multiply(BigDecimal.valueOf(3)));}
 @Test void newProfilesHaveCompleteRecommendationPreferences(){var user=new Domain.User();assertEquals("None",user.spice);assertEquals("Regular",user.tasteIntensity);assertTrue(user.allergens.isEmpty());}
 @Test void optionSeedIsValidJsonShape(){assertTrue(SeedData.optionsFor("Tomato Pasta").startsWith("[{"));assertEquals("[]",SeedData.optionsFor("Unknown"));}
 @Test void aiServiceGracefullyFallsBackWhenNoApiKey(){
  var ai = new AiService("", "https://api.openai.com/v1", "gpt-4o-mini", 5);
  var user = new Domain.User();
  user.preferredCategories = java.util.Set.of("Light Meal");
  var d1 = new Domain.Dish(); d1.id = 1L; d1.name = "Caesar Salad"; d1.category = "Light Meal"; d1.protein = "Chicken"; d1.spice = "None"; d1.description = "Fresh salad";
  var d2 = new Domain.Dish(); d2.id = 2L; d2.name = "Burger"; d2.category = "Western"; d2.protein = "Beef"; d2.spice = "None"; d2.description = "Beef burger";
  var recs = ai.recommend(user, java.util.List.of(d1, d2), "I want something light with chicken");
  assertFalse(recs.isEmpty());
  assertEquals(1L, recs.get(0).dishId());
  assertTrue(recs.get(0).reason().contains("light"));
 }
}
