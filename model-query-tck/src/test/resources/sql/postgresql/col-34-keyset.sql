select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 2,1 fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id>? or r1_0.id is null or r1_0.id=? and oe1_0.id>? order by 2,1 fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is null and oe1_0.id>? order by 2,1 fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is null and oe1_0.id>? order by 2,1 fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 2 desc,1 desc fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is not null or r1_0.id is null and oe1_0.id<? order by 2 desc,1 desc fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is not null or r1_0.id is null and oe1_0.id<? order by 2 desc,1 desc fetch first ? rows only
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id<? or r1_0.id=? and oe1_0.id<? order by 2 desc,1 desc fetch first ? rows only
