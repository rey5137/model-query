update orders oe1_0 set status=?,version=(oe1_0.version+cast(? as integer)) where exists(select 1 from orders oe2_0 join customers c1_0 on c1_0.id=oe2_0.customer_id left join customers r1_0 on r1_0.id=oe2_0.referrer_id where oe2_0.id=oe1_0.id and c1_0.country=? and r1_0.country<>? and oe2_0.id<?)
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED'
