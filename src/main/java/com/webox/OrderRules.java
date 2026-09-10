package com.webox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

final class OrderRules {
 static final String BUSINESS_TIME_ZONE = "Asia/Shanghai";
 private static final ObjectMapper JSON = new ObjectMapper();

 private OrderRules() {}

 record Slot(LocalDate date,String slot) {}
 record OptionSelection(@NotBlank @Size(max=100) String group,@NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> choices) {}
 record PricedOptions(BigDecimal price,String description) {}
 private record OptionGroup(String name,boolean required,List<List<Object>> choices) {}

 static Slot nextSlot(LocalDateTime now,LocalDate requestedDate,String requestedSlot) {
  var date=requestedDate==null?now.toLocalDate():requestedDate;
  if(date.isAfter(now.toLocalDate()))return new Slot(date,requestedSlot);
  if("Lunch".equals(requestedSlot)&&now.toLocalTime().isBefore(LocalTime.of(10,0)))return new Slot(date,requestedSlot);
  if(now.toLocalTime().isBefore(LocalTime.of(15,0)))return new Slot(date,"Dinner");
  return new Slot(date.plusDays(1),"Lunch");
 }

 static List<Api.Item> lockOrder(List<Api.Item> items) {
  return items.stream().sorted(Comparator.comparing(Api.Item::dishId)).toList();
 }

 static PricedOptions priceOptions(String optionsJson,List<OptionSelection> requested) {
  List<OptionGroup> groups;
  try {
   var type=JSON.getTypeFactory().constructCollectionType(List.class,OptionGroup.class);
   groups=JSON.readValue(optionsJson==null||optionsJson.isBlank()?"[]":optionsJson,type);
  } catch (JsonProcessingException | IllegalArgumentException e) {
   throw badRequest("Dish options are not configured correctly");
  }
  var selections=requested==null?List.<OptionSelection>of():requested;
  var byGroup=new LinkedHashMap<String,List<String>>();
  for(var selection:selections){
   if(selection==null||selection.group()==null||selection.choices()==null||byGroup.putIfAbsent(selection.group(),selection.choices())!=null)
    throw badRequest("Invalid option selection");
  }
  var total=BigDecimal.ZERO;var labels=new ArrayList<String>();
  for(var group:groups){
   if(group==null||group.name()==null||group.name().isBlank()||group.choices()==null)throw badRequest("Dish options are not configured correctly");
   var chosen=byGroup.remove(group.name());
   if(chosen==null)chosen=List.of();
   if(group.required()&&chosen.size()!=1)throw badRequest(group.name()+" requires one selection");
   if(group.required()&&chosen.size()>1)throw badRequest(group.name()+" allows only one selection");
   if(new HashSet<>(chosen).size()!=chosen.size())throw badRequest("Duplicate option selection");
   var prices=new HashMap<String,BigDecimal>();
   for(var choice:group.choices()){
    if(choice==null||choice.size()!=2)throw badRequest("Dish options are not configured correctly");
    var name=String.valueOf(choice.get(0));
    BigDecimal price;
    try {price=new BigDecimal(String.valueOf(choice.get(1)));}
    catch(NumberFormatException e){throw badRequest("Dish options are not configured correctly");}
    if(name.isBlank()||price.signum()<0||price.scale()>2||prices.putIfAbsent(name,price)!=null)throw badRequest("Dish options are not configured correctly");
   }
   for(var name:chosen){
    var price=prices.get(name);
    if(price==null)throw badRequest("Unknown option: "+name);
    total=total.add(price);
   }
   if(!chosen.isEmpty())labels.add(group.name()+": "+String.join(", ",chosen));
  }
  if(!byGroup.isEmpty())throw badRequest("Unknown option group: "+byGroup.keySet().iterator().next());
  return new PricedOptions(total,String.join(" · ",labels));
 }

 private static ResponseStatusException badRequest(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
