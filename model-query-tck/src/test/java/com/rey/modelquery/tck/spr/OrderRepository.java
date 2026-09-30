package com.rey.modelquery.tck.spr;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository that extends the model-query fragment next to {@code JpaRepository} (R-SPR-12). */
interface OrderRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<OrderEntity> {}
