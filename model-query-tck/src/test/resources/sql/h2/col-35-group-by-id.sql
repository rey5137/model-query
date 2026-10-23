select r1_0.id c0,count(*) c1 from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by c0 order by 1
select r1_0.id c0,count(*) c1 from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by c0 order by 1 offset ? rows fetch first ? rows only
select r1_0.id c0,count(*) c1 from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by c0 order by 1 offset ? rows fetch first ? rows only
select r1_0.id c0,count(*) c1 from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by c0 order by 1 offset ? rows fetch first ? rows only
