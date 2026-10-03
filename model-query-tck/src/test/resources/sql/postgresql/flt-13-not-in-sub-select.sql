select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where oe1_0.referrer_id not in ((select oe2_0.id from orders oe2_0 where oe2_0.status=? and oe2_0.id is not null)) or oe1_0.referrer_id is null order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where oe1_0.id not in ((select oe2_0.referrer_id from orders oe2_0 where oe2_0.referrer_id is not null)) or oe1_0.id is null order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where oe1_0.id not in ((select poie1_0.order_id from order_items poie1_0 where 1<>1 and poie1_0.order_id is not null)) or oe1_0.id is null order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where oe1_0.referrer_id is null order by 1
