package com.webox;
import org.springframework.boot.CommandLineRunner; import org.springframework.context.annotation.*; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.*;
@Configuration class SeedData {
 @Bean CommandLineRunner seed(DishRepository dishes,MenuRepository menus,UserRepository users){return args->{
  if(users.findByEmailIgnoreCase("admin@webox.com").isEmpty()){var u=new Domain.User();u.email="admin@webox.com";u.passwordHash=new BCryptPasswordEncoder().encode("Admin123");u.role="ADMIN";users.save(u);}
  if(dishes.count()>0)return;
  add(dishes,menus,"Kung Pao Chicken","Classic Sichuan chicken stir-fried with peanuts and dried chili","22.00","Chinese","Chicken","Medium",Set.of("Peanuts"),"https://images.unsplash.com/photo-1525755662778-989d0524087e?auto=format&fit=crop&w=900&q=80","[]",18);
  add(dishes,menus,"Caesar Salad","Fresh romaine lettuce with Parmesan and Caesar dressing","28.50","Light Meal","None","None",Set.of("Dairy","Egg"),"https://images.unsplash.com/photo-1546793665-c74683f339c1?auto=format&fit=crop&w=900&q=80","[{\"name\":\"Add-ons\",\"required\":false,\"choices\":[[\"Grilled Chicken\",6],[\"Bacon\",5],[\"Avocado\",4]]}]",12);
  add(dishes,menus,"Salmon Sashimi Set","Fresh salmon sashimi with rice and miso soup","45.00","Japanese","Fish","None",Set.of("Fish"),"https://images.unsplash.com/photo-1579871494447-9811cf80d66c?auto=format&fit=crop&w=900&q=80","[]",8);
  add(dishes,menus,"Tomato Pasta","Classic Italian tomato pasta with fresh basil","26.50","Western","None","None",Set.of("Gluten"),"https://images.unsplash.com/photo-1473093295043-cdd812d0e601?auto=format&fit=crop&w=900&q=80","[]",3);
  add(dishes,menus,"Chicken Quinoa Bowl","Low-fat high-protein grilled chicken with quinoa, avocado and vegetables","35.80","Light Meal","Chicken","None",Set.of(),"https://images.unsplash.com/photo-1547592180-85f173990554?auto=format&fit=crop&w=900&q=80","[]",14);
  add(dishes,menus,"Korean Bibimbap","Stone-pot mixed rice with vegetables, fried egg and chili sauce","30.00","Korean","Egg","Mild",Set.of("Egg","Soy"),"https://images.unsplash.com/photo-1553163147-622ab57be1c7?auto=format&fit=crop&w=900&q=80","[]",10);
  add(dishes,menus,"Classic Beef Burger","Angus beef patty with lettuce, tomato and onion","38.00","Western","Beef","None",Set.of("Gluten","Dairy"),"https://images.unsplash.com/photo-1568901346375-23c9450c58cd?auto=format&fit=crop&w=900&q=80","[]",9);
 };}
 static void add(DishRepository dr,MenuRepository mr,String n,String de,String p,String c,String protein,String spice,Set<String>a,String image,String opts,int stock){var d=new Domain.Dish();d.name=n;d.description=de;d.price=new BigDecimal(p);d.category=c;d.protein=protein;d.spice=spice;d.allergens=a;d.image=image;d.optionsJson=opts;dr.save(d);for(int x=0;x<2;x++){var m=new Domain.DailyMenu();m.dish=d;m.menuDate=LocalDate.now().plusDays(x);m.stock=stock;mr.save(m);}}
}
