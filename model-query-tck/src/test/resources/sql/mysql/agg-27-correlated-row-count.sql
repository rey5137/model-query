select oe1_0.id,(select count(*) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status) from orders oe1_0 where oe1_0.id<=? order by 1
