select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id in (?,?,?) for update
select oie1_0.id,oie1_0.product_code,oie1_0.quantity,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id where o1_0.id in (?,?,?) order by 1
