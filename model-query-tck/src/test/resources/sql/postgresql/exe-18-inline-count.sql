select oe1_0.id,oe1_0.status,oe1_0.total,count(1) over() from orders oe1_0 where oe1_0.status=? order by 1 offset ? rows fetch first ? rows only
select oe1_0.id,oe1_0.status,oe1_0.total,count(1) over() from orders oe1_0 where oe1_0.status=? order by 1 offset ? rows fetch first ? rows only
select c1_0.id,sum(oe1_0.total),count(1) over() from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 order by 1 offset ? rows fetch first ? rows only
select c1_0.id,sum(oe1_0.total),count(1) over() from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 having sum(oe1_0.total)>? order by 1 offset ? rows fetch first ? rows only
select oe1_0.id,count(1) over() from orders oe1_0 where oe1_0.status=? order by 1 offset ? rows fetch first ? rows only
select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.status=? and oe1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by 1
