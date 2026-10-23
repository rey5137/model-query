select count(*) from orders oe1_0 where oe1_0.id<=?
select count(*) from orders oe1_0 where oe1_0.id<=?
select count(*) from orders oe1_0 where oe1_0.id<=?
select count(*) from orders oe1_0 where oe1_0.id<=? and (select count(*) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status)=?
select count(*) from orders oe1_0 where oe1_0.id<=? and case when exists(select 1 from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status) then true else false end=?
select count(*) from orders oe1_0 where oe1_0.id<=? and (select sum(oe2_0.total) from orders oe2_0 where oe2_0.id<=? and (oe2_0.status<>? or oe2_0.status is null) and oe2_0.status=oe1_0.status) is null
