package io.conddo.core.repository;

import io.conddo.core.domain.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderIdOrderByCreatedAt(UUID orderId);

    /** Top line items by total quantity (a "top products" proxy, §11.9): rows of [description, qty]. */
    @Query("select i.description, sum(i.quantity) from OrderItem i "
            + "group by i.description order by sum(i.quantity) desc")
    List<Object[]> topItemsByQuantity(Pageable pageable);
}
