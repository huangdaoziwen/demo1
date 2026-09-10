package com.webox;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class Domain {
 @Entity(name="User") @Table(name="users", uniqueConstraints=@UniqueConstraint(columnNames="email"))
 @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY) static class User {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @Column(nullable=false,length=200) public String email;
  @JsonIgnore @Column(nullable=false) public String passwordHash; @Column(nullable=false) public String role="EMPLOYEE"; @JsonIgnore public String token;
  @ElementCollection(fetch=FetchType.EAGER) public Set<String> allergens=new HashSet<>(); @ElementCollection(fetch=FetchType.EAGER) public Set<String> preferredCategories=new HashSet<>();
  public String spice="None"; public String tasteIntensity="Regular"; public Integer budgetMin=1500,budgetMax=4000;
 }
 @Entity(name="Dish") @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY) static class Dish {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @Column(nullable=false,length=100) public String name;
  @Column(nullable=false,length=500) public String description; @Column(nullable=false,precision=10,scale=2) public BigDecimal price;
  public String category,protein,spice,image; public boolean active=true; @ElementCollection(fetch=FetchType.EAGER) public Set<String> allergens=new HashSet<>();
  @Column(length=2000) public String optionsJson="[]";
 }
 @Entity(name="DailyMenu") @Table(uniqueConstraints=@UniqueConstraint(columnNames={"dish_id","menuDate"}))
 @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY) static class DailyMenu {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @ManyToOne(optional=false) public Dish dish;
  public LocalDate menuDate; public int stock; @Version public long version;
 }
 @Entity(name="MealOrder") @Table(name="meal_orders",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","mealDate","slot","activeKey"}))
 @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY) static class MealOrder {
  @Id public String id; @JsonIgnore @ManyToOne(optional=false) public User user; public LocalDate mealDate; public String slot,status,address; @JsonIgnore public String idempotencyKey;
  @Column(precision=10,scale=2) public BigDecimal total; public Instant createdAt; public String activeKey="ACTIVE";
  @OneToMany(fetch=FetchType.EAGER,cascade=CascadeType.ALL,orphanRemoval=true,mappedBy="order") public List<OrderItem> items=new ArrayList<>();
 }
 @Entity(name="OrderItem") @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY) static class OrderItem {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @JsonIgnore @ManyToOne public MealOrder order; @ManyToOne public Dish dish;
  public int quantity; @Column(precision=10,scale=2) public BigDecimal unitPrice,subtotal; @Column(length=1000) public String selections;
 }
}
