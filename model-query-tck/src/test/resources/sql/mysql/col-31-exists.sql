select ce1_0.id,ce1_0.name,ce1_0.country from customers ce1_0 where exists(select 1 from orders oe1_0 where oe1_0.status=? and oe1_0.referrer_id=ce1_0.id) order by 1
