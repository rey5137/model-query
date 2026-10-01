update orders oe1_0 set status=?,version=(oe1_0.version+cast(? as integer)) where exists(select 1 from order_items i1_0 where i1_0.quantity=? and oe1_0.id=i1_0.order_id) and oe1_0.id<?
select oe1_0.id from orders oe1_0 where oe1_0.status='MARKED'
