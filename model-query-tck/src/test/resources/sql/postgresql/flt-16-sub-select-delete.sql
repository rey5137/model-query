delete from order_items oie1_0 where oie1_0.id in ((select oie2_0.id from order_items oie2_0 join orders o1_0 on o1_0.id=oie2_0.order_id where o1_0.status=?)) and oie1_0.id<=?
select oie1_0.id from order_items oie1_0 where oie1_0.id<=300
