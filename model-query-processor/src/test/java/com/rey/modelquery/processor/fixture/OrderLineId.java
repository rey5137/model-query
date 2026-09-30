package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public class OrderLineId implements Serializable {

    private static final long serialVersionUID = 1L;

    protected Long orderId;

    protected Integer lineNo;
}
