select oe1_0.id from orders oe1_0 where oe1_0.referrer_id is null order by 1
select oe1_0.id from orders oe1_0 where oe1_0.referrer_id is not null order by 1
select oe1_0.id from orders oe1_0 where oe1_0.referrer_id=? and oe1_0.referrer_id is not null order by 1
select oe1_0.id from orders oe1_0 where oe1_0.referrer_id<>? or oe1_0.referrer_id is null order by 1
select oe1_0.id from orders oe1_0 where not(oe1_0.referrer_id=? and oe1_0.referrer_id is not null) order by 1
select oe1_0.id from orders oe1_0 where not(oe1_0.referrer_id<>? or oe1_0.referrer_id is null) order by 1
select oe1_0.id from orders oe1_0 where oe1_0.referrer_id is not null order by 1
select oe1_0.id from orders oe1_0 where oe1_0.referrer_id is null order by 1
