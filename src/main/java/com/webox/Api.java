package com.webox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.cache.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
class Api {
 private final UserRepository users;
 private final DishRepository dishes;
 private final MenuRepository menus;
 private final OrderRepository orders;
 private final InventoryEvents inventoryEvents;
 private final StorageConfig storage;
 private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
 Api(UserRepository u,DishRepository d,MenuRepository m,OrderRepository o,InventoryEvents events,StorageConfig storage){users=u;dishes=d;menus=m;orders=o;inventoryEvents=events;this.storage=storage;}

 record Credentials(@NotBlank @Email @Size(max=200) String email,
                    @NotBlank @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{8,72}$",message="Password must contain letters and numbers and be at least 8 characters") String password){}
 record Session(String token,String role,String email){}
 record UserProfile(Long id,String email,String role,Set<String> allergens,Set<String> preferredCategories,String spice,String tasteIntensity,Integer budgetMin,Integer budgetMax){}
 record PreferencesPayload(Set<String> allergens,Set<String> preferredCategories,
                           @Pattern(regexp="None|Mild|Medium|Hot") String spice,
                           @Pattern(regexp="Light|Regular|Heavy") String tasteIntensity,
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
  u.spice=p.spice()==null?"None":p.spice();u.tasteIntensity=p.tasteIntensity()==null?"Regular":p.tasteIntensity();u.budgetMin=p.budgetMin()==null?0:p.budgetMin();u.budgetMax=p.budgetMax()==null?4000:p.budgetMax();
  return profileOf(users.save(u));
 }
 private UserProfile profileOf(Domain.User u){return new UserProfile(u.id,u.email,u.role,Set.copyOf(u.allergens),Set.copyOf(u.preferredCategories),u.spice,u.tasteIntensity,u.budgetMin,u.budgetMax);}
 private Domain.User me(String auth){
  if(auth==null||!auth.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in");
  return users.findByToken(auth.substring(7)).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please sign in"));
 }
 private Domain.User admin(String auth){var u=me(auth);if(!"ADMIN".equals(u.role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator access required");return u;}

 record MenuDish(Long id,String name,String description,BigDecimal price,String category,String protein,String spice,Set<String> allergens,String image,String optionsJson,int stock){}
 @Cacheable(value="menus",key="(#date == null ? T(java.time.LocalDate).now() : #date).toString() + ':' + #q.toLowerCase()")
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
 @CacheEvict(value="menus",allEntries=true) @PostMapping("/orders") @Transactional OrderDto place(@RequestHeader("Authorization") String auth,@Valid @RequestBody Place p){
  var u=me(auth);var old=orders.findByUserIdAndIdempotencyKey(u.id,p.idempotencyKey());if(old.isPresent())return orderDto(old.get());
  int count=p.items().stream().mapToInt(Item::quantity).sum();if(count>5)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An order can contain at most 5 meals");
  var target=nextSlot(p.date(),p.slot());if(orders.findByUserIdAndMealDateAndSlotAndActiveKey(u.id,target.date,target.slot,"ACTIVE").isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"You already have an active order for this meal");
  var o=new Domain.MealOrder();o.id="WB-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();o.user=u;o.mealDate=target.date;o.slot=target.slot;o.address=p.address();o.status="Pending";o.createdAt=Instant.now();o.idempotencyKey=p.idempotencyKey();o.total=BigDecimal.ZERO;
  for(var in:p.items()){
   var menu=menus.lock(target.date,in.dishId()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"A selected dish is unavailable"));
   if(menu.stock<in.quantity())throw new ResponseStatusException(HttpStatus.CONFLICT,menu.dish.name+" has only "+menu.stock+" remaining");menu.stock-=in.quantity();
   var i=new Domain.OrderItem();i.order=o;i.dish=menu.dish;i.quantity=in.quantity();i.selections=in.selections()==null?"":in.selections();i.unitPrice=menu.dish.price.add(in.optionPrice()==null?BigDecimal.ZERO:in.optionPrice());i.subtotal=i.unitPrice.multiply(BigDecimal.valueOf(i.quantity));o.total=o.total.add(i.subtotal);o.items.add(i);
  }
  var saved=orders.save(o);o.items.forEach(i->menus.findByMenuDateAndDishId(o.mealDate,i.dish.id).ifPresent(m->inventoryEvents.publish(o.mealDate,i.dish.id,m.stock)));return orderDto(saved);
 }
 record Slot(LocalDate date,String slot){}
 private Slot nextSlot(LocalDate d,String s){var now=LocalDateTime.now();var date=d==null?now.toLocalDate():d;if(date.isAfter(now.toLocalDate()))return new Slot(date,s);if("Lunch".equals(s)&&now.toLocalTime().isBefore(LocalTime.of(10,0)))return new Slot(date,s);if(now.toLocalTime().isBefore(LocalTime.of(15,0)))return new Slot(date,"Dinner");return new Slot(date.plusDays(1),"Lunch");}
 @GetMapping("/orders") List<OrderDto> listOrders(@RequestHeader("Authorization") String auth){return orders.findByUserIdOrderByCreatedAtDesc(me(auth).id).stream().map(this::orderDto).toList();}
 @CacheEvict(value="menus",allEntries=true) @PostMapping("/orders/{id}/cancel") @Transactional OrderDto cancel(@RequestHeader("Authorization") String auth,@PathVariable String id){
  var u=me(auth);var o=orders.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));if(!o.user.id.equals(u.id))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(!o.status.equals("Pending"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending orders can be cancelled");o.status="Cancelled";o.activeKey=o.id;
  for(var i:o.items)menus.lock(o.mealDate,i.dish.id).ifPresent(m->{m.stock+=i.quantity;inventoryEvents.publish(o.mealDate,i.dish.id,m.stock);});return orderDto(orders.save(o));
 }
 private OrderDto orderDto(Domain.MealOrder o){return new OrderDto(o.id,o.mealDate,o.slot,o.status,o.address,o.total,o.createdAt,o.items.stream().map(i->new OrderItemDto(i.id,i.dish.id,i.dish.name,i.dish.image,i.quantity,i.unitPrice,i.subtotal,i.selections)).toList());}

 record DishPayload(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=500) String description,@NotNull @DecimalMin("0.01") BigDecimal price,
                    @NotBlank String category,@NotBlank String protein,@NotBlank String spice,@Size(max=1000) String image,Set<String> allergens,@Size(max=2000) String optionsJson,Boolean active){}
 record DishDto(Long id,String name,String description,BigDecimal price,String category,String protein,String spice,String image,Set<String> allergens,String optionsJson,boolean active){}
 @GetMapping("/admin/dishes") List<DishDto> adminDishes(@RequestHeader("Authorization") String auth){admin(auth);return dishes.findAllByOrderByIdAsc().stream().map(this::dishDto).toList();}
 @CacheEvict(value="menus",allEntries=true) @PostMapping("/admin/dishes") DishDto createDish(@RequestHeader("Authorization") String auth,@Valid @RequestBody DishPayload p){admin(auth);return dishDto(dishes.save(apply(new Domain.Dish(),p)));}
 @CacheEvict(value="menus",allEntries=true) @PutMapping("/admin/dishes/{id}") DishDto updateDish(@RequestHeader("Authorization") String auth,@PathVariable Long id,@Valid @RequestBody DishPayload p){admin(auth);var d=dishes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));return dishDto(dishes.save(apply(d,p)));}
 @CacheEvict(value="menus",allEntries=true) @PatchMapping("/admin/dishes/{id}/status") DishDto toggleDish(@RequestHeader("Authorization") String auth,@PathVariable Long id){admin(auth);var d=dishes.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));d.active=!d.active;return dishDto(dishes.save(d));}
 private Domain.Dish apply(Domain.Dish d,DishPayload p){d.name=p.name().trim();d.description=p.description().trim();d.price=p.price();d.category=p.category();d.protein=p.protein();d.spice=p.spice();d.image=p.image()==null?"":p.image();d.allergens=p.allergens()==null?new HashSet<>():new HashSet<>(p.allergens());d.optionsJson=p.optionsJson()==null||p.optionsJson().isBlank()?"[]":p.optionsJson();if(p.active()!=null)d.active=p.active();return d;}
 private DishDto dishDto(Domain.Dish d){return new DishDto(d.id,d.name,d.description,d.price,d.category,d.protein,d.spice,d.image,Set.copyOf(d.allergens),d.optionsJson,d.active);}

 record MenuStock(Long dishId,String dishName,String category,boolean active,int stock){}
 record MenuStockInput(@NotNull Long dishId,@Min(0) int stock){}
 record MenuSchedule(@NotNull LocalDate date,@NotNull List<@Valid MenuStockInput> items){}
 @GetMapping("/admin/menu") List<MenuStock> adminMenu(@RequestHeader("Authorization") String auth,@RequestParam LocalDate date){
  admin(auth);var stock=new HashMap<Long,Integer>();menus.findByMenuDate(date).forEach(m->stock.put(m.dish.id,m.stock));return dishes.findAllByOrderByIdAsc().stream().map(d->new MenuStock(d.id,d.name,d.category,d.active,stock.getOrDefault(d.id,0))).toList();
 }
 @CacheEvict(value="menus",allEntries=true) @PostMapping("/admin/menu") @Transactional List<MenuStock> saveMenu(@RequestHeader("Authorization") String auth,@Valid @RequestBody MenuSchedule p){
  admin(auth);for(var item:p.items()){var d=dishes.findById(item.dishId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Dish not found"));var m=menus.findByMenuDateAndDishId(p.date(),d.id).orElseGet(()->{var created=new Domain.DailyMenu();created.menuDate=p.date();created.dish=d;return created;});m.stock=item.stock();menus.save(m);inventoryEvents.publish(p.date(),d.id,m.stock);}return adminMenu(auth,p.date());
 }

 @GetMapping(path="/inventory/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter inventoryStream(){return inventoryEvents.subscribe();}

 record UploadResult(String url){}
 @PostMapping(path="/admin/uploads",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 UploadResult upload(@RequestHeader("Authorization") String auth,@RequestPart("file") MultipartFile file) throws IOException {
  admin(auth);if(file.isEmpty()||file.getContentType()==null||!file.getContentType().startsWith("image/"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid image file");
  if(file.getSize()>5_000_000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Image must be 5 MB or smaller");
  var ext=Optional.ofNullable(file.getOriginalFilename()).filter(n->n.contains(".")).map(n->n.substring(n.lastIndexOf('.')).toLowerCase(Locale.ROOT)).filter(e->Set.of(".jpg",".jpeg",".png",".webp",".gif").contains(e)).orElse(".img");
  Files.createDirectories(storage.uploadDir());var name=UUID.randomUUID()+ext;try(var input=file.getInputStream()){Files.copy(input,storage.uploadDir().resolve(name),StandardCopyOption.REPLACE_EXISTING);}return new UploadResult("/uploads/"+name);
 }

 record Overview(long totalOrders,BigDecimal revenue,long pending,long completed,long cancelled){}
 record NamedValue(String name,long value){}
 record TrendPoint(LocalDate date,long orders,BigDecimal revenue){}
 record LowStock(Long dishId,String name,int stock){}
 record Metrics(Overview overview,List<NamedValue> topDishes,List<NamedValue> slots,List<TrendPoint> trend,List<LowStock> lowStock,Instant generatedAt){}
 @GetMapping("/admin/metrics") Metrics metrics(@RequestHeader("Authorization") String auth){
  admin(auth);var today=LocalDate.now();var all=orders.findByMealDateBetween(today.minusDays(6),today);var todays=all.stream().filter(o->o.mealDate.equals(today)).toList();
  var revenue=todays.stream().filter(o->!"Cancelled".equals(o.status)).map(o->o.total).reduce(BigDecimal.ZERO,BigDecimal::add);
  var overview=new Overview(todays.size(),revenue,todays.stream().filter(o->"Pending".equals(o.status)).count(),todays.stream().filter(o->"Completed".equals(o.status)).count(),todays.stream().filter(o->"Cancelled".equals(o.status)).count());
  var dishSales=new HashMap<String,Long>();all.stream().filter(o->!"Cancelled".equals(o.status)).flatMap(o->o.items.stream()).forEach(i->dishSales.merge(i.dish.name,(long)i.quantity,Long::sum));
  var top=dishSales.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(10).map(e->new NamedValue(e.getKey(),e.getValue())).toList();
  var slots=List.of("Lunch","Dinner").stream().map(slot->new NamedValue(slot,todays.stream().filter(o->!"Cancelled".equals(o.status)&&slot.equals(o.slot)).count())).toList();
  var trend=new ArrayList<TrendPoint>();for(int x=6;x>=0;x--){var date=today.minusDays(x);var day=all.stream().filter(o->o.mealDate.equals(date)&&!"Cancelled".equals(o.status)).toList();trend.add(new TrendPoint(date,day.size(),day.stream().map(o->o.total).reduce(BigDecimal.ZERO,BigDecimal::add)));}
  var low=menus.findByMenuDate(today).stream().filter(m->m.dish.active&&m.stock<=3).sorted(Comparator.comparingInt(m->m.stock)).map(m->new LowStock(m.dish.id,m.dish.name,m.stock)).toList();return new Metrics(overview,top,slots,trend,low,Instant.now());
 }

 record AssistantRequest(@NotBlank @Size(max=500) String message){}
 record Recommendation(Long dishId,String reason){}
 @PostMapping(path="/assistant/recommend",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 SseEmitter recommend(@RequestHeader("Authorization") String auth,@Valid @RequestBody AssistantRequest request){
  var u=me(auth);var emitter=new SseEmitter(60_000L);CompletableFuture.runAsync(()->{
   try{var today=LocalDate.now();var recent=orders.findByUserIdAndMealDateGreaterThanEqual(u.id,today.minusDays(7)).stream().flatMap(o->o.items.stream()).map(i->i.dish.id).collect(java.util.stream.Collectors.toSet());var words=request.message().toLowerCase(Locale.ROOT);
    var candidates=menus.findByMenuDateAndDishActiveTrue(today).stream().filter(m->m.stock>0&&!recent.contains(m.dish.id)&&Collections.disjoint(m.dish.allergens,u.allergens)).sorted(Comparator.<Domain.DailyMenu>comparingInt(m->assistantScore(m.dish,u,words)).reversed()).limit(3).toList();
    emitter.send(SseEmitter.event().name("intro").data("I considered your preferences, allergies, live stock, and meals from the last 7 days."));
    for(var m:candidates){var reason=assistantReason(m.dish,u,words);emitter.send(SseEmitter.event().name("recommendation").data(new Recommendation(m.dish.id,reason)));Thread.sleep(220);}
    if(candidates.isEmpty())emitter.send(SseEmitter.event().name("intro").data("No safe, in-stock dishes remain after applying your allergy and 7-day history rules."));emitter.send(SseEmitter.event().name("done").data("done"));emitter.complete();
   }catch(Exception e){emitter.completeWithError(e);}});return emitter;
 }
 private int assistantScore(Domain.Dish d,Domain.User u,String words){int score=u.preferredCategories.contains(d.category)?4:0;score+=u.spice.equals(d.spice)?2:0;String text=(d.name+" "+d.description+" "+d.category+" "+d.protein).toLowerCase(Locale.ROOT);for(var word:words.split("\\W+"))if(word.length()>2&&text.contains(word))score+=3;if((words.contains("light")||words.contains("low-fat"))&&"Light Meal".equals(d.category))score+=6;if((words.contains("protein")||words.contains("high-protein"))&&!"None".equals(d.protein))score+=5;return score;}
 private String assistantReason(Domain.Dish d,Domain.User u,String words){var reasons=new ArrayList<String>();if(u.preferredCategories.contains(d.category))reasons.add("matches your "+d.category+" preference");if(u.spice.equals(d.spice))reasons.add("fits your "+d.spice.toLowerCase(Locale.ROOT)+" spice setting");if(words.contains("protein")&&!"None".equals(d.protein))reasons.add("offers "+d.protein.toLowerCase(Locale.ROOT)+" protein");if("Light".equals(u.tasteIntensity)||words.contains("light"))reasons.add("suits a lighter meal");return "Recommended because it "+(reasons.isEmpty()?"best matches your request":String.join(" and ",reasons))+".";}
}
