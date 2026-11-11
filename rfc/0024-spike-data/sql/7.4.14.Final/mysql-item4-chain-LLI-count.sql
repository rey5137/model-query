select count(*) from sp_order so1_0 left join sp_customer sc1_0 on sc1_0.id=so1_0.customer_id left join sp_region sr1_0 on sr1_0.id=sc1_0.region_id join sp_country sc2_0 on sc2_0.id=sr1_0.country_id;
-- binds []

