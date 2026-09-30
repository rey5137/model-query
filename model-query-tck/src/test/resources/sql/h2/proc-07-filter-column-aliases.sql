select oe1_0.id,oe1_0.status,oe1_0.referrer_id from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=? and i1_0.quantity=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.referrer_id from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id join order_items i2_0 on oe1_0.id=i2_0.order_id where i1_0.quantity=? and i2_0.quantity=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.referrer_id from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.quantity=? and i1_0.quantity=? order by 1
