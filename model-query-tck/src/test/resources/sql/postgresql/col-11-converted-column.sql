select oe1_0.id,oe1_0.status from orders oe1_0 where (oe1_0.status<>? or oe1_0.status is null) and oe1_0.status in (?,?,?) and (oe1_0.id not in (?,?) or oe1_0.id is null) and oe1_0.id>? and oe1_0.id between ? and ? and oe1_0.id>=? order by 1
select oe1_0.id,oe1_0.status from orders oe1_0 where oe1_0.status=? order by 1 fetch first ? rows only
