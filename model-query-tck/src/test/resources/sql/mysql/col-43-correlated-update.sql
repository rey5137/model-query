select oe1_0.id from orders oe1_0 where oe1_0.id<=? and (select count(oe2_0.id) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status)=? order by 1 limit ?
update orders oe1_0 set status=?,version=(oe1_0.version+?) where oe1_0.id in (?,?,?,?) and oe1_0.id<=?
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED' order by oe1_0.id
