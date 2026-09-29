select oe1_0.status,r1_0.name,oe1_0.id,oe1_0.total from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 4 desc,3
select oe1_0.id from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id order by oe1_0.total desc,1
select oe1_0.status,r1_0.name,oe1_0.id,oe1_0.total from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 4 desc,3
select oe1_0.status,r1_0.name,oe1_0.id from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by 3
select oe1_0.id from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by 1
select oe1_0.status,r1_0.name,oe1_0.id from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by 3
select oe1_0.status,c1_0.name,oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by 2,3
select oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by c1_0.name,1
select oe1_0.status,c1_0.name,oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.name<? or oe1_0.status=? order by 2,3
