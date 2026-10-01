select oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id left join customers r1_0 on r1_0.id=oe1_0.referrer_id where c1_0.country=? and r1_0.country<>? and oe1_0.id<? order by 1 limit ?
update orders oe1_0 set status=?,version=(oe1_0.version+?) where oe1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) and oe1_0.id<?
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED'
