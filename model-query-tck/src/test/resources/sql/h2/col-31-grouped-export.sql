select ce1_0.country c0,count(*) c1 from customers ce1_0 where ce1_0.country=? group by c0 order by 1 offset ? rows fetch first ? rows only
