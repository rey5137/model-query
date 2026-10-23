select oe1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.referrer_id<=>r1_0.id order by 1
select oe1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where not(oe1_0.referrer_id<=>r1_0.id) order by 1
select oe1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where not(oe1_0.referrer_id<=>r1_0.id) order by 1
select oe1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.referrer_id<=>r1_0.id order by 1
select oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.referrer_id<=>c1_0.id order by 1
select oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where not(oe1_0.referrer_id<=>c1_0.id) order by 1
select oe1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where not(oe1_0.referrer_id<=>c1_0.id) order by 1
select oe1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.referrer_id<=>c1_0.id order by 1
