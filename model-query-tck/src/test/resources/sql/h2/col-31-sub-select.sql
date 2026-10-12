select ce1_0.id,ce1_0.name,ce1_0.country from customers ce1_0 where ce1_0.id in ((select oe1_0.referrer_id from orders oe1_0 where oe1_0.status=?)) order by 1
