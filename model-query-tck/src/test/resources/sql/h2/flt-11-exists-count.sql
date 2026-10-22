select count(oe1_0.id) from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.product_code=? and i1_0.quantity>=? and oe1_0.id=i1_0.order_id)
select count(oe1_0.id) from orders oe1_0 where exists(select 1 from orders oe2_0 join order_items i1_0 on oe2_0.id=i1_0.order_id where oe2_0.id=oe1_0.id and i1_0.product_code=? and i1_0.quantity>=?)
