select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 limit ?,?
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 limit ?,?
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 limit ?,?
