select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 asc nulls first
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 asc nulls first offset ? rows fetch first ? rows only
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 asc nulls first offset ? rows fetch first ? rows only
select r1_0.id,count(*) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where oe1_0.id<=? group by 1 order by 1 asc nulls first offset ? rows fetch first ? rows only
