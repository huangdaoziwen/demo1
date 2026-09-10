package com.webox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class WeboxApplication {
 public static void main(String[] args) {
  SpringApplication.run(WeboxApplication.class,args);
 }
}
