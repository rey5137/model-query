select oe1_0.status,count(oe1_0.id),sum(oe1_0.total) from orders oe1_0 group by 1 order by 1
