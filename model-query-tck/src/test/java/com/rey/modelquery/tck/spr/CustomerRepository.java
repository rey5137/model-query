package com.rey.modelquery.tck.spr;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository of customers, which a bulk delete can remove, unlike orders with their tags (R-SPR-10). */
interface CustomerRepository extends JpaRepository<CustomerEntity, Long>, ModelQueryRepository<CustomerEntity> {}
