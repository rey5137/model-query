select soe1_0.status c0,max(soe1_0.placed_at) c1 from orders soe1_0 group by c0 order by 1
