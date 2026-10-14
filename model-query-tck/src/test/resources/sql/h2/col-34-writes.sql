update orders oe1_0 set status=?,version=(oe1_0.version+cast(? as integer)) where oe1_0.id<? and (oe1_0.referrer_id is null or oe1_0.referrer_id=?)
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED'
delete from cards ce1_0 where ce1_0.holder_id=?
