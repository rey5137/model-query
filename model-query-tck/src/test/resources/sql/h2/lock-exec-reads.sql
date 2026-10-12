select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 fetch first ? rows only for update
select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 order by 1 fetch first ? rows only for update
select oe1_0.id,oe1_0.status,oe1_0.total from orders oe1_0 where oe1_0.id=? fetch first ? rows only for update
