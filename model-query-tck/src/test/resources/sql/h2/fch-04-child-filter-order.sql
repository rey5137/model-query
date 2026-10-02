select ce1_0.id,ce1_0.name from customers ce1_0 where ce1_0.id<=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,c1_0.id from orders oe1_0 left join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status=? and c1_0.id in (?,?,?,?,?,?,?,?,?,?) order by 3 desc,1
