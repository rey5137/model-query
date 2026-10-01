select soe1_0.status,min(soe1_0.placed_at),max(soe1_0.placed_at),max(soe1_0.placed_at),count(distinct soe1_0.placed_at) from orders soe1_0 group by 1 order by 1
