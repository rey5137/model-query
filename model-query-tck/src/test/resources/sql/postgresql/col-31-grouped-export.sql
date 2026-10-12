select ce1_0.country,count(ce1_0.id) from customers ce1_0 where ce1_0.country=? group by 1 order by 1 offset ? rows fetch first ? rows only
