select oe1_0.id from orders oe1_0 where exists(select 1 from orders oe2_0 where oe2_0.id<>oe1_0.id and oe2_0.referrer_id is not distinct from oe1_0.referrer_id) order by 1
select oe1_0.id from orders oe1_0 where not exists(select 1 from orders oe2_0 where oe2_0.id<>oe1_0.id and oe2_0.referrer_id is not distinct from oe1_0.referrer_id) order by 1
select oe1_0.id from orders oe1_0 where exists(select 1 from orders oe2_0 where oe2_0.id<>oe1_0.id and oe2_0.referrer_id is distinct from oe1_0.referrer_id) order by 1
select oe1_0.id from orders oe1_0 where not exists(select 1 from orders oe2_0 where oe2_0.id<>oe1_0.id and oe2_0.referrer_id is not distinct from oe1_0.referrer_id) order by 1
