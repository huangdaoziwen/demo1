package com.webox;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class Domain {
 @Entity(name="User") @Table(name="users", uniqueConstraints=@UniqueConstraint(columnNames="email")) static class User {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; @Column(nullable=false,length=200) String email;
  @JsonIgnore @Column(nullable=false) String passwordHash; @Column(nullable=false) String role="EMPLOYEE"; @JsonIgnore String token;
  @ElementCollection(fetch=FetchType.EAGER) Set<String> allergens=new HashSet<>(); @ElementCollection(fetch=FetchType.EAGER) Set<String> preferredCategories=new HashSet<>();
  String spice="None"; Integer budgetMin=1500,budgetMax=4000;
 }
 @Entity(name="Dish") static class Dish {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; @Column(nullable=false,length=100) String name;
  @Column(nullable=false,length=500) String description; @Column(nullable=false,precision=10,scale=2) BigDecimal price;
  String category,protein,spice,image; boolean active=true; @ElementCollection(fetch=FetchType.EAGER) Set<String> allergens=new HashSet<>();
  @Column(length=2000) String optionsJson="[]";
 }
 @Entity(name="DailyMenu") @Table(uniqueConstraints=@UniqueConstraint(columnNames={"dish_id","menuDate"})) static class DailyMenu {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; @ManyToOne(optional=false) Dish dish;
  LocalDate menuDate; int stock; @Version long version;
 }
 @Entity(name="MealOrder") @Table(name="meal_orders",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","mealDate","slot","activeKey"})) static class MealOrder {
  @Id String id; @JsonIgnore @ManyToOne(optional=false) User user; LocalDate mealDate; String slot,status,address; @JsonIgnore String idempotencyKey;
  @Column(precision=10,scale=2) BigDecimal total; Instant createdAt; String activeKey="ACTIVE";
  @OneToMany(cascade=CascadeType.ALL,orphanRemoval=true,mappedBy="order",fetch=FetchType.EAGER) List<OrderItem> items=new ArrayList<>();
 }
 @Entity(name="OrderItem") static class OrderItem {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id; @JsonIgnore @ManyToOne MealOrder order; @ManyToOne Dish dish;
  int quantity; @Column(precision=10,scale=2) BigDecimal unitPrice,subtotal; @Column(length=1000) String selections;
 }
}
