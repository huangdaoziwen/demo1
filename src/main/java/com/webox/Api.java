package com.webox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
class Api {
 private final UserRepository users;
 private final DishRepository dishes;
 private final MenuRepository menus;
 private final OrderRepository orders;
 private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
 Api(UserRepository u,DishRepository d,MenuRepository m,OrderRepository o){users=u;dishes=d;menus=m;orders=o;}

 record Credentials(@NotBlank @Email @Size(max=200) String email,
                    @NotBlank @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{8,72}$",message="Password must contain letters and numbers and be at least 8 characters") String password){}
 record Session(String token,String role,String email){}
 record UserProfile(Long id,String email,String role,Set<String> allergens,Set<String> preferredCategories,String spice,Integer budgetMin,Integer budgetMax){}
 record PreferencesPayload(Set<String> allergens,Set<String> preferredCategories,
                           @Pattern(regexp="None|Mild|Medium|Hot") String spice,
                           @Min(0) @Max(10000) Integer budgetMin,@Min(0) @Max(10000) Integer budgetMax){}

 @PostMapping("/auth/register")
 Session register(@Valid @RequestBody Credentials c){
  if(users.findByEmailIgnoreCase(c.email()).isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already registered");
  var u=new Domain.User();u.email=c.email().trim().toLowerCase();u.passwordHash=bcrypt.encode(c.password());u.token=UUID.randomUUID().toString();users.save(u);
  return new Session(u.token,u.role,u.email);
 }
 @PostMapping("/auth/login")
 Session login(@Valid @RequestBody Credentials c){
  var u=users.findByEmailIgnoreCase(c.email()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Incorrect email or password"));
  if(!bcrypt.matches(c.password(),u.passwordHash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Incorrect email or password");
  u.token=UUID.randomUUID().toString();users.save(u);return new Session(u.token,u.role,u.email);
 }
 @GetMapping("/me") UserProfile profile(@RequestHeader("Authorization") String auth){return profileOf(me(auth));}
 @PutMapping("/me/preferences") UserProfile preferences(@RequestHeader("Authorization") String auth,@Valid @RequestBody PreferencesPayload p){
  var u=me(auth);
  if(p.budgetMin()!=null&&p.budgetMax()!=null&&p.budgetMin()>p.budgetMax())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Minimum budget cannot exceed maximum budget");
  u.allergens=p.allergens()==null?new HashSet<>():new HashSet<>(p.allergens());
  u.preferredCategories=p.preferredCategories()==null?new HashSet<>():new HashSet<>(p.preferredCategories());
  u.spice=p.spice()==null?"None":p.spice();u.budgetMin=p.budgetMin()==null?0:p.budgetMin();u.budgetMax=p.budgetMax()==null?4000:p.budgetMax();
  return profileOf(users.save(u));
 }
 private UserProfile profileOf(Domain.User u){return new UserProfile(u.id,u.email,u.role,Set.copyOf(u.allergens),Set.copyOf(u.preferredCategories),u.spice,u.budgetMin,u.budgetMax);}
 private Domain.User me(String auth){
  if(auth==null||!auth.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in");
  return users.findByToken(auth.substring(7)).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in"));
 }
 private Domain.User admin(String auth){var u=me(auth);if(!"ADMIN".equals(u.role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator access required");return u;}

 record MenuDish(Long id,String name,String description,BigDecimal price,String category,String protein,String spice,Set<String> allergens,String image,String optionsJson,int stock){}
 @GetMapping("/menu") List<MenuDish> menu(@RequestParam(defaultValue="") @Size(max=50) String q,@RequestParam(required=false) LocalDate date){
  var day=date==null?LocalDate.now():date;var query=q.toLowerCase(Locale.ROOT);
  return menus.findByMenuDateAndDishActiveTrue(day).stream().filter(m->(m.dish.name+" "+m.dish.description).toLowerCase(Locale.ROOT).contains(query)).map(this::menuDto).toList();
 }
 private MenuDish menuDto(Domain.DailyMenu m){var d=m.dish;return new MenuDish(d.id,d.name,d.description,d.price,d.category,d.protein,d.spice,Set.copyOf(d.allergens),d.image,d.optionsJson,m.stock);}

 record Item(@NotNull Long dishId,@Min(1) @Max(5) int quantity,@Size(max=1000) String selections,@DecimalMin("0") BigDecimal optionPrice){}
 record Place(@NotBlank @Size(max=80) String idempotencyKey,LocalDate date,@NotNull @Pattern(regexp="Lunch|Dinner") String slot,@NotBlank @Size(max=200) String address,@NotEmpty List<@Valid Item> items){}
 record OrderItemDto(Long id,Long dishId,String dishName,String image,int quantity,BigDecimal unitPrice,BigDecimal subtotal,String selections){}
 record OrderDto(String id,LocalDate mealDate,String slot,String status,String address,BigDecimal total,Instant createdAt,List<OrderItemDto> items){}
 record OrderCheck(boolean hasActiveOrder,String orderId){}

 @GetMapping("/orders/check") OrderCheck check(@RequestHeader("Authorization") String auth,@RequestParam LocalDate date,@RequestParam @Pattern(regexp="Lunch|Dinner") String slot){
  var u=me(auth);var found=orders.findByUserIdAndMealDateAndSlotAndActiveKey(u.id,date,slot,"ACTIVE");return new OrderCheck(found.isPresent(),found.map(o->o.id).orElse(null));
 }
 @PostMapping("/orders") @Transactional OrderDto place(@RequestHeader("Authorization") String auth,@Valid @RequestBody Place p){
  var u=me(auth);var old=orders.findByUserIdAndIdempotencyKey(u.id,p.idempotencyKey());if(old.isPresent())return orderDto(old.get());
  int count=p.items().stream().mapToInt(Item::quantity).sum();if(count>5)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An order can contain at most 5 meals");
  var target=nextSlot(p.date(),p.slot());if(orders.findByUserIdAndMealDateAndSlotAndActiveKey(u.id,target.date,target.slot,"ACTIVE").isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"You already have an active order for this meal");
  var o=new Domain.MealOrder();o.id="WB-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();o.user=u;o.mealDate=target.date;o.slot=target.slot;o.address=p.address();o.status="Pending";o.createdAt=Instant.now();o.idempotencyKey=p.idempotencyKey();o.total=BigDecimal.ZERO;
  for(var in:p.items()){
   var menu=menus.lock(target.date,in.dishId()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"A selected dish is unavailable"));
   if(menu.stock<in.quantity())throw new ResponseStatusException(HttpStatus.CONFLICT,menu.dish.name+" has only "+menu.stock+" remaining");menu.stock-=in.quantity();
   var i=new Domain.OrderItem();i.order=o;i.dish=menu.dish;i.quantity=in.quantity();i.selections=in.selections()==null?"":in.selections();i.unitPrice=menu.dish.price.add(in.optionPrice()==null?BigDecimal.ZERO:in.optionPrice());i.subtotal=i.unitPrice.multiply(BigDecimal.valueOf(i.quantity));o.total=o.total.add(i.subtotal);o.items.add(i);
  }
  return orderDto(orders.save(o));
 }
 record Slot(LocalDate date,String slot){}
 private Slot nextSlot(LocalDate d,String s){var now=LocalDateTime.now();var date=d==null?now.toLocalDate():d;if(date.isAfter(now.toLocalDate()))return new Slot(date,s);if("Lunch".equals(s)&&now.toLocalTime().isBefore(LocalTime.of(10,0)))return new Slot(date,s);if(now.toLocalTime().isBefore(LocalTime.of(15,0)))return new Slot(date,"Dinner");return new Slot(date.plusDays(1),"Lunch");}
 @GetMapping("/orders") List<OrderDto> listOrders(@RequestHeader("Authorization") String auth){return orders.findByUserIdOrderByCreatedAtDesc(me(auth).id).stream().map(this::orderDto).toList();}
 @PostMapping("/orders/{id}/cancel") @Transactional OrderDto cancel(@RequestHeader("Authorization") String auth,@PathVariable String id){
  var u=me(auth);var o=orders.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));if(!o.user.id.equals(u.id))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(!o.status.equals("Pending"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending orders can be cancelled");o.status="Cancelled";o.activeKey=o.id;
  for(var i:o.items)menus.lock(o.mealDate,i.dish.id).ifPresent(m->m.stock+=i.quantity);return orderDto(orders.save(o));
 }
 private OrderDto orderDto(Domain.MealOrder o){return new OrderDto(o.id,o.mealDate,o.slot,o.status,o.address,o.total,o.createdAt,o.items.stream().map(i->new OrderItemDto(i.id,i.dish.id,i.dish.name,i.dish.image,i.quantity,i.unitPrice,i.subtotal,i.selections)).toList());}

 record DishPayload(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=500) String description,@NotNull @DecimalMin("0.01") BigDecimal price,
                    @NotBlank String category,@NotBlank String protein,@NotBlank String spice,@Size(max=1000) String image,Set<String> allergens,@Size(max=2000) String optionsJson,Boolean active){}
 record DishDto(Long id,String name,String description,BigDecimal price,String category,String protein,String spice,String image,Set<String> allergens,String optionsJson,boolean active){}
 @GetMapping("/admin/dishes") List<DishDto> adminDishes(@RequestHeader("Authorization") String auth){admin(auth);return dishes.findAllByOrderByIdAsc().stream().map(this::dishDto).toList();}
 @PostMapping("/admin/dishes") DishDto createDish(@RequestHeader("Authorization") String auth,@Valid @RequestBody DishPayload p){admin(auth);return dishDto(dishes.save(apply(new Domain.Dish(),p)));}
 @PutMapping("/admin/dishes/{id}") DishDto updateDish(@RequestHeader("Authorization") String auth,@PathVariable Long id,@Valid @RequestBody DishPayload p){admin(auth);var d=dishes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));return dishDto(dishes.save(apply(d,p)));}
 @PatchMapping("/admin/dishes/{id}/status") DishDto toggleDish(@RequestHeader("Authorization") String auth,@PathVariable Long id){admin(auth);var d=dishes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));d.active=!d.active;return dishDto(dishes.save(d));}
 private Domain.Dish apply(Domain.Dish d,DishPayload p){d.name=p.name().trim();d.description=p.description().trim();d.price=p.price();d.category=p.category();d.protein=p.protein();d.spice=p.spice();d.image=p.image()==null?"":p.image();d.allergens=p.allergens()==null?new HashSet<>():new HashSet<>(p.allergens());d.optionsJson=p.optionsJson()==null||p.optionsJson().isBlank()?"[]":p.optionsJson();if(p.active()!=null)d.active=p.active();return d;}
 private DishDto dishDto(Domain.Dish d){return new DishDto(d.id,d.name,d.description,d.price,d.category,d.protein,d.spice,d.image,Set.copyOf(d.allergens),d.optionsJson,d.active);}

 record MenuStock(Long dishId,String dishName,String category,boolean active,int stock){}
 record MenuStockInput(@NotNull Long dishId,@Min(0) int stock){}
 record MenuSchedule(@NotNull LocalDate date,@NotNull List<@Valid MenuStockInput> items){}
 @GetMapping("/admin/menu") List<MenuStock> adminMenu(@RequestHeader("Authorization") String auth,@RequestParam LocalDate date){
  admin(auth);var stock=new HashMap<Long,Integer>();menus.findByMenuDate(date).forEach(m->stock.put(m.dish.id,m.stock));return dishes.findAllByOrderByIdAsc().stream().map(d->new MenuStock(d.id,d.name,d.category,d.active,stock.getOrDefault(d.id,0))).toList();
 }
 @PostMapping("/admin/menu") @Transactional List<MenuStock> saveMenu(@RequestHeader("Authorization") String auth,@Valid @RequestBody MenuSchedule p){
  admin(auth);for(var item:p.items()){var d=dishes.findById(item.dishId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));var m=menus.findByMenuDateAndDishId(p.date(),d.id).orElseGet(()->{var created=new Domain.DailyMenu();created.menuDate=p.date();created.dish=d;return created;});m.stock=item.stock();menus.save(m);}return adminMenu(auth,p.date());
 }
}
