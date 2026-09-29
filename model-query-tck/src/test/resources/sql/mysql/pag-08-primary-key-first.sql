select oie1_0.id,oie1_0.product_code,oie1_0.quantity from order_items oie1_0 where oie1_0.product_code=? order by 3 desc,1 limit ?,?
select oie1_0.id from order_items oie1_0 where oie1_0.product_code=? order by oie1_0.quantity desc,1 limit ?,?
select oie1_0.id,oie1_0.product_code,oie1_0.quantity from order_items oie1_0 where oie1_0.product_code=? and oie1_0.id in (?,?,?,?,?,?) order by 3 desc,1
