select oe1_0.id,oe1_0.status,c1_0.id,c2_0.id,r1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers c2_0 on c2_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? order by 1
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?) order by 1
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?) order by 1
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?,?) order by 1
select oe1_0.id,oe1_0.status,c1_0.id,c2_0.id,r1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers c2_0 on c2_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? order by 1 offset ? rows fetch first ? rows only
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?,?,?,?) order by 1
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?,?,?,?,?) order by 1
select oe1_0.id,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.id in (?,?) order by 1
