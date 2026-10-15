select cte01_0.mq_id,cte01_0.mq_status,cte01_0.mq_total from (select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0) cte01_0(mq_id,mq_status,mq_total) order by 1
