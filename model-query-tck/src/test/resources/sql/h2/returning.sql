select id, quantity, order_id from final table (update order_items oie1_0 set quantity=? where oie1_0.id=?)
select id, quantity, order_id from old table (delete from order_items oie1_0 where oie1_0.id=?)
