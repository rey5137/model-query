with cte0 (mq_id,mq_status,mq_total) as (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0) select cte01_0.mq_id,cte01_0.mq_status,cte01_0.mq_total from cte0 cte01_0 order by 1
