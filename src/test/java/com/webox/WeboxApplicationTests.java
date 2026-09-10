package com.webox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeboxApplicationTests {
 @Test void applicationHasBootAndCacheConfiguration() {
  assertTrue(WeboxApplication.class.isAnnotationPresent(SpringBootApplication.class));
  assertTrue(WeboxApplication.class.isAnnotationPresent(EnableCaching.class));
 }

 @Test void currencyUsesExactDecimalArithmetic() {
  assertEquals(new BigDecimal("79.50"),new BigDecimal("26.50").multiply(BigDecimal.valueOf(3)));
 }

 @Test void newProfilesHaveCompleteRecommendationPreferences() {
  var user=new Domain.User();
  assertEquals("None",user.spice);
  assertEquals("Regular",user.tasteIntensity);
  assertTrue(user.allergens.isEmpty());
 }

 @Test void optionSeedIsValidJsonShape() {
  assertTrue(SeedData.optionsFor("Tomato Pasta").startsWith("[{"));
  assertEquals("[]",SeedData.optionsFor("Unknown"));
 }

 @Test void optionPriceIsCalculatedFromServerConfiguration() {
  var selected=List.of(
   new OrderRules.OptionSelection("Pasta",List.of("Penne")),
   new OrderRules.OptionSelection("Add-ons",List.of("Extra Parmesan","Grilled Chicken"))
  );
  var priced=OrderRules.priceOptions(SeedData.optionsFor("Tomato Pasta"),selected);
  assertEquals(new BigDecimal("9"),priced.price());
  assertEquals("Pasta: Penne · Add-ons: Extra Parmesan, Grilled Chicken",priced.description());
 }

 @Test void unknownOrMissingOptionsAreRejected() {
  var options=SeedData.optionsFor("Tomato Pasta");
  assertThrows(ResponseStatusException.class,()->OrderRules.priceOptions(options,List.of()));
  assertThrows(ResponseStatusException.class,()->OrderRules.priceOptions(options,List.of(
   new OrderRules.OptionSelection("Pasta",List.of("Free Pasta"))
  )));
 }

 @Test void menuRowsAreLockedInStableDishOrder() {
  var sorted=OrderRules.lockOrder(List.of(new Api.Item(9L,1,List.of()),new Api.Item(2L,1,List.of())));
  assertEquals(List.of(2L,9L),sorted.stream().map(Api.Item::dishId).toList());
 }

 @Test void cutoffRulesUseTheProvidedBusinessTime() {
  var day=LocalDate.of(2026,9,10);
  assertEquals(new OrderRules.Slot(day,"Lunch"),OrderRules.nextSlot(LocalDateTime.of(2026,9,10,9,59),day,"Lunch"));
  assertEquals(new OrderRules.Slot(day,"Dinner"),OrderRules.nextSlot(LocalDateTime.of(2026,9,10,10,0),day,"Lunch"));
  assertEquals(new OrderRules.Slot(day.plusDays(1),"Lunch"),OrderRules.nextSlot(LocalDateTime.of(2026,9,10,15,0),day,"Dinner"));
 }

 @Test void aiServiceGracefullyFallsBackWhenNoApiKey() {
  var ai=new AiService("","https://api.openai.com/v1","gpt-4o-mini",5);
  var user=new Domain.User();user.preferredCategories=java.util.Set.of("Light Meal");
  var salad=new Domain.Dish();salad.id=1L;salad.name="Caesar Salad";salad.category="Light Meal";salad.protein="Chicken";salad.spice="None";salad.description="Fresh salad";
  var burger=new Domain.Dish();burger.id=2L;burger.name="Burger";burger.category="Western";burger.protein="Beef";burger.spice="None";burger.description="Beef burger";
  var recommendations=ai.recommend(user,List.of(salad,burger),"I want something light with chicken");
  assertFalse(recommendations.isEmpty());
  assertEquals(1L,recommendations.get(0).dishId());
  assertTrue(recommendations.get(0).reason().contains("light"));
 }
}
