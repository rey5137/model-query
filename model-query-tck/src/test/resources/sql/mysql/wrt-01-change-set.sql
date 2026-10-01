select oe1_0.status,oe1_0.total,oe1_0.customer_id,oe1_0.referrer_id,oe1_0.version from orders oe1_0 where oe1_0.id=?
update orders oe1_0 set status=?,referrer_id=null,version=(oe1_0.version+?) where oe1_0.id=?
select oe1_0.status,oe1_0.total,oe1_0.customer_id,oe1_0.referrer_id,oe1_0.version from orders oe1_0 where oe1_0.id=?
