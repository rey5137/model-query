delete from order_items oie1_0 where oie1_0.id<=? and (select count(*) from order_items oie2_0 where oie2_0.id<=? and oie2_0.product_code=oie1_0.product_code)=?
select oie1_0.id from order_items oie1_0 where oie1_0.id<=75 order by oie1_0.id
