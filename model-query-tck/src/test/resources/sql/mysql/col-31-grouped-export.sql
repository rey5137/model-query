select ce1_0.country,count(*) from customers ce1_0 where ce1_0.country=? group by 1 order by 1 limit ?,?
