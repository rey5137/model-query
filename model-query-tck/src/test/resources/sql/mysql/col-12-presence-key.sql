select oie1_0.id,o1_0.referrer_id,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id<=? order by 1
select oie1_0.id,r1_0.name,r1_0.id,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id left join customers r1_0 on r1_0.id=o1_0.referrer_id where oie1_0.id<=? order by 1
select oie1_0.id,r1_0.id,r1_0.name,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id left join customers r1_0 on r1_0.id=o1_0.referrer_id where oie1_0.id<=? order by 1
select oie1_0.id from order_items oie1_0 where oie1_0.id<=? order by 1
