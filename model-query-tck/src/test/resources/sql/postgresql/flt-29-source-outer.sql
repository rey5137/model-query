with cte0 (mq_id,mq_status,mq_total) as (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id<=?) select cte01_0.mq_id,(select count(*) from customers ce1_0 where ce1_0.id=cte01_0.mq_id) from cte0 cte01_0 order by 1
with cte0 (mq_id,mq_status,mq_total) as (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id<=?) select count(1) from cte0 cte01_0
