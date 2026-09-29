select ce1_0.id from customers ce1_0 left join orders o1_0 on ce1_0.id=o1_0.customer_id where ce1_0.id>=? and (o1_0.status=? or ce1_0.name=?) order by 1
