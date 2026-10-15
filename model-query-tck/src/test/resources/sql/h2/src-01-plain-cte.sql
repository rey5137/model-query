select cte01_0.id,cte01_0.status,cte01_0.total from (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0) cte01_0(id,status,total) order by 1
