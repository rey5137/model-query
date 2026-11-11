select var_1_21_0.mq_spent c0,var_1_41_0.mq_spent c1 from sp_order so1_0 left join (select sp1_0.customer_id c0,sum(sp1_0.amount) c1,count(*) c2 from sp_payment sp1_0 group by c0) var_1_21_0(mq_customerId,mq_spent,mq_payments) on var_1_21_0.mq_customerId=so1_0.customer_id left join (select sp2_0.customer_id c0,sum(sp2_0.amount) c1,count(*) c2 from sp_payment sp2_0 where sp2_0.amount>=? group by c0) var_1_41_0(mq_customerId,mq_spent,mq_payments) on var_1_41_0.mq_customerId=so1_0.referrer_id group by c0,c1;
-- binds [2.00]

