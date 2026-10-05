select (oie1_0.quantity+cast(? as integer)) c0,count(oie1_0.id) c1 from order_items oie1_0 where (oie1_0.quantity+cast(? as integer))>? group by c0 order by 1
