package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/**
 * A source whose fields are SQL reserved words on some vendor ({@code rank}, {@code year}): the CTE's columns are
 * named {@code mq_<property>}, so none needs quoting (D-146).
 */
@SourceModel(root = OrderEntity.class)
public record ReservedSource(@PrimaryKey Long id, @Column(attribute = "total") BigDecimal rank,
        @Column(attribute = "status") String year) {}
