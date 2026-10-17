select cte01_0.mq_id,(select count(ce1_0.id) from customers ce1_0 where ce1_0.id=cte01_0.mq_id) from (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id<=?) cte01_0(mq_id,mq_status,mq_total) order by 1
select count(1) from (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id<=?) cte01_0(mq_id,mq_status,mq_total)
