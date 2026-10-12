select ce1_0.id,timezone('UTC',timezone(?,ce1_0.created_at)) from customers ce1_0 where ce1_0.id<=? group by 1,2 order by 1
