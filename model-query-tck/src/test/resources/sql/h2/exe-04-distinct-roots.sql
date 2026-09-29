select count(distinct oe1_0.id) from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=?
