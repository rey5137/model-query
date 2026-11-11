select so1_0.id,so1_0.customer_id,sc1_0.id,sc1_0.name,sc1_0.email from sp_order so1_0 left join sp_customer sc1_0 on sc1_0.id=so1_0.customer_id order by 1;
-- binds []

