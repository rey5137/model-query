select oie1_0.id,r1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id left join customers r1_0 on r1_0.id=o1_0.referrer_id where oie1_0.id<=? order by 1
select ce1_0.id from customers ce1_0 where ce1_0.id<=? order by 1
select oe1_0.id,r1_0.id from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id where r1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by 1
select che1_0.id,c1_0.id from card_holders che1_0 left join cards c1_0 on che1_0.id=c1_0.holder_id order by 2,1
select ce1_0.id,h1_0.id,i1_0.id from cards ce1_0 left join card_holders h1_0 on h1_0.id=ce1_0.holder_id left join customers i1_0 on i1_0.id=ce1_0.issuer_id order by 1
