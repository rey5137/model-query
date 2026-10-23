select (oie1_0.quantity+cast(? as integer)),count(*) from order_items oie1_0 where (oie1_0.quantity+?)>? group by 1 order by 1
