select soe1_0.status c0,min(soe1_0.placed_at) c1,max(soe1_0.placed_at) c2,max(soe1_0.placed_at) c3,count(distinct soe1_0.placed_at) c4 from orders soe1_0 group by c0 order by 1
