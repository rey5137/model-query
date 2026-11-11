select so1_0.id,sc1_0.name,sr1_0.name,sc2_0.name from sp_order so1_0 join sp_customer sc1_0 on sc1_0.id=so1_0.customer_id join sp_region sr1_0 on sr1_0.id=sc1_0.region_id join sp_country sc2_0 on sc2_0.id=sr1_0.country_id order by 1;
-- binds []

