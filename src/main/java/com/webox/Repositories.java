package com.webox;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType;
import java.time.LocalDate; import java.util.*;
interface UserRepository extends JpaRepository<Domain.User,Long>{Optional<Domain.User> findByEmailIgnoreCase(String email);Optional<Domain.User> findByToken(String token);}
interface DishRepository extends JpaRepository<Domain.Dish,Long>{List<Domain.Dish> findByActiveTrueOrderById();List<Domain.Dish> findAllByOrderByIdAsc();}
interface MenuRepository extends JpaRepository<Domain.DailyMenu,Long>{
 List<Domain.DailyMenu> findByMenuDateAndDishActiveTrue(LocalDate date);
 List<Domain.DailyMenu> findByMenuDate(LocalDate date);
 Optional<Domain.DailyMenu> findByMenuDateAndDishId(LocalDate date,Long dishId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from DailyMenu m where m.menuDate=:date and m.dish.id=:dish") Optional<Domain.DailyMenu> lock(@Param("date") LocalDate date,@Param("dish") Long dish);
}
interface OrderRepository extends JpaRepository<Domain.MealOrder,String>{
 Optional<Domain.MealOrder> findByUserIdAndIdempotencyKey(Long user,String key);
 List<Domain.MealOrder> findByUserIdOrderByCreatedAtDesc(Long user);
 Optional<Domain.MealOrder> findByUserIdAndMealDateAndSlotAndActiveKey(Long u,LocalDate d,String s,String a);
 List<Domain.MealOrder> findByMealDateBetween(LocalDate from,LocalDate to);
 List<Domain.MealOrder> findByUserIdAndMealDateGreaterThanEqual(Long user,LocalDate from);
}
