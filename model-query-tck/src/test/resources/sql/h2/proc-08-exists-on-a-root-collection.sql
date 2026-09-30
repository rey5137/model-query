select oe1_0.id,oe1_0.status,oe1_0.referrer_id from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.product_code=? and oe1_0.id=i1_0.order_id) order by 1
select ce1_0.id,ce1_0.name,ce1_0.country from customers ce1_0 where exists(select 1 from orders o1_0 where ce1_0.id=o1_0.customer_id) order by 1
