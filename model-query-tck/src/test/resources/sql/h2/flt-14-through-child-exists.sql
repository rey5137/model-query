select le1_0.id from labels le1_0 where le1_0.id in (?,?,?) order by 1
select o1_1.id,o1_1.status,o1_1.total,le1_0.id from labels le1_0 join order_labels o1_0 on le1_0.id=o1_0.label_id join orders o1_1 on o1_1.id=o1_0.order_id where exists(select 1 from order_items poie1_0 where poie1_0.product_code=? and poie1_0.order_id=o1_1.id) and le1_0.id in (?,?,?) order by 1
