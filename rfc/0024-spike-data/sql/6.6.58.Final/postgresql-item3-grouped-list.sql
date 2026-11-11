with cte0 (mq_customerId,mq_spent,mq_payments) as (select sp1_0.customer_id,sum(sp1_0.amount),count(*) from sp_payment sp1_0 group by 1),cte1 (mq_customerId,mq_spent,mq_payments) as (select sp2_0.customer_id,sum(sp2_0.amount),count(*) from sp_payment sp2_0 where sp2_0.amount>=? group by 1) select cte01_0.mq_spent,cte11_0.mq_spent,count(*) from sp_order so1_0 left join cte0 cte01_0 on cte01_0.mq_customerId=so1_0.customer_id left join cte1 cte11_0 on cte11_0.mq_customerId=so1_0.referrer_id group by 1,2 order by 1,2;
-- binds [2.00]

