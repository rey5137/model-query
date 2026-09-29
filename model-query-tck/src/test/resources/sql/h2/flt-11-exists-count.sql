select count(oe1_0.id) from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.product_code=? and i1_0.quantity>=? and oe1_0.id=i1_0.order_id)
select count(distinct oe1_0.id) from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=? and i1_0.quantity>=?
select count(oe1_0.id) from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=? and i1_0.quantity>=?
