select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.id in (?,?,?) or oe1_0.id in (?,?,?) or oe1_0.id in (?) order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.id in (?,?,?,?,?,?,?) order by 1
