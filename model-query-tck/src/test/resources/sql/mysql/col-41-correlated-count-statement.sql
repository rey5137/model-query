select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=?
select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=?
select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=?
select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=? and (select count(oe2_0.id) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status)=?
select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=? and case when exists(select 1 from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status) then 1 else 0 end=?
select count(oe1_0.id) from orders oe1_0 where oe1_0.id<=? and (select sum(oe2_0.total) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status) is null
