select le1_0.id from labels le1_0 where le1_0.id<=? order by 1
select o1_1.id,o1_1.status,o1_1.total,le1_0.id from labels le1_0 join order_labels o1_0 on le1_0.id=o1_0.label_id join orders o1_1 on o1_1.id=o1_0.order_id where o1_1.status=? and le1_0.id in (?,?,?) order by 3 desc,1
select oie1_0.id,oie1_0.product_code,oie1_0.quantity,o1_0.id from order_items oie1_0 left join orders o1_0 on o1_0.id=oie1_0.order_id where o1_0.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by 1
