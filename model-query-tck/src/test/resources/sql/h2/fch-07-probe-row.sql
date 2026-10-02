select ce1_0.id,ce1_0.name from customers ce1_0 where ce1_0.id<=? order by 2 desc,1 offset ? rows fetch first ? rows only
select oe1_0.id,oe1_0.status,oe1_0.total,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?) order by 1
select oie1_0.id,oie1_0.product_code,oie1_0.quantity,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id where o1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by 1
select ce1_0.id from customers ce1_0 where ce1_0.id<=? order by ce1_0.name desc,1 offset ? rows fetch first ? rows only
select ce1_0.id,ce1_0.name from customers ce1_0 where ce1_0.id<=? and ce1_0.id in (?,?,?,?) order by 2 desc,1
select oe1_0.id,oe1_0.status,oe1_0.total,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?) order by 1
select oie1_0.id,oie1_0.product_code,oie1_0.quantity,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id where o1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by 1
