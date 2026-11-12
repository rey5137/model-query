package com.rey.modelquery.tck.spr;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.OrderEntity;
import org.springframework.data.repository.Repository;

/** A second repository of {@code OrderEntity}, told apart from {@link OrderRepository} by its interface (R-SPR-18). */
interface OrderReportRepository extends Repository<OrderEntity, Long>, ModelQueryRepository<OrderEntity> {}
