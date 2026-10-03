select count(oe1_0.id) from orders oe1_0 where oe1_0.id in ((select poie1_0.order_id from order_items poie1_0 where poie1_0.product_code=?))
select count(oe1_0.id) from orders oe1_0 where exists(select 1 from order_items poie1_0 where poie1_0.product_code=? and poie1_0.order_id=oe1_0.id)
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where oe1_0.id in ((select poie1_0.order_id from order_items poie1_0 where poie1_0.product_code=?)) order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.referrer_id from orders oe1_0 where exists(select 1 from order_items poie1_0 where poie1_0.product_code=? and poie1_0.order_id=oe1_0.id) order by 1
