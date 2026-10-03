update orders soe1_0 set status=? where exists(select 1 from customers c1_0 join orders o1_0 on c1_0.id=o1_0.customer_id where o1_0.status=? and c1_0.id=soe1_0.customer_id) and soe1_0.id<?
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED'
