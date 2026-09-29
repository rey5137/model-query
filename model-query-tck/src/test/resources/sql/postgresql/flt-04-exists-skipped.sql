select oe1_0.id from orders oe1_0 order by 1
select oe1_0.id from orders oe1_0 where exists(select 1 from order_items i1_0 where oe1_0.id=i1_0.order_id) order by 1
