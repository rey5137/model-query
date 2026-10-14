select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 1
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is null order by 1
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id=? order by 1
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id order by 2,1 offset ? rows fetch first ? rows only
select count(oe1_0.id) from orders oe1_0
select count(oe1_0.id) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id is null
