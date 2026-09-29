select oe1_0.status,count(oe1_0.id) from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=? or oe1_0.status=? group by 1 having sum(i1_0.unit_price)>? order by 1
