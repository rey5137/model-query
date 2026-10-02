select le1_0.id from labels le1_0 order by 1
select c1_0.id,c1_0.name,le1_0.id from labels le1_0 join order_labels o1_0 on le1_0.id=o1_0.label_id join orders o1_1 on o1_1.id=o1_0.order_id join customers c1_0 on c1_0.id=o1_1.customer_id where le1_0.id in (?,?,?,?,?,?,?,?,?,?) order by 1
