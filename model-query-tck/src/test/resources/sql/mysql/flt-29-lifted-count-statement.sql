select count(*) from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=?
select count(*) from orders oe1_0 join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? and (select count(*) from customers ce1_0 where ce1_0.id<=? and ce1_0.name=r1_0.name)=?
select count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? and ((select count(*) from customers ce1_0 where ce1_0.id<=? and ce1_0.name=r1_0.name)=? or oe1_0.id=?)
select count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? and (select count(*) from customers ce1_0 where ce1_0.id<=? and ce1_0.name=r1_0.name)<>?
