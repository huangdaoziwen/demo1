package com.webox;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
class InventoryEvents {
 record StockUpdate(LocalDate date,Long dishId,int stock){}
 private final List<SseEmitter> clients=new CopyOnWriteArrayList<>();

 SseEmitter subscribe(){
  var emitter=new SseEmitter(0L);clients.add(emitter);
  emitter.onCompletion(()->clients.remove(emitter));emitter.onTimeout(()->clients.remove(emitter));emitter.onError(e->clients.remove(emitter));
  try{emitter.send(SseEmitter.event().name("connected").data("ok"));}catch(IOException e){clients.remove(emitter);}
  return emitter;
 }
 void publish(LocalDate date,Long dishId,int stock){
  var event=SseEmitter.event().name("stock_update").data(new StockUpdate(date,dishId,stock));
  clients.removeIf(client->{try{client.send(event);return false;}catch(IOException e){client.complete();return true;}});
 }
}
