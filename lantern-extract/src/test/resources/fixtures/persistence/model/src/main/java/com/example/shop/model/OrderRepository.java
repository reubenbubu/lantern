package com.example.shop.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

  @Query(value = "SELECT * FROM orders o JOIN order_line l ON l.order_id = o.id", nativeQuery = true)
  List<Order> withLines();

  @Modifying
  @Query(value = "DELETE FROM " + Tables.ORDERS + " WHERE id = ?1", nativeQuery = true)
  void remove(long id);

  /** JPQL names entities, not tables: no facts. */
  @Query("SELECT o FROM Order o")
  List<Order> all();
}
