select oe1_0.status c0,count(oe1_0.id) c1,sum(oe1_0.total) c2,min(oe1_0.placed_at) c3 from orders oe1_0 group by c0 order by 1
