update order_items oie1_0 set quantity=? where oie1_0.id=? returning id,quantity,order_id
delete from order_items oie1_0 where oie1_0.id=? returning id,quantity,order_id
insert into counter_rows as cre1_0(id,next_value) values (?,?) returning id,next_value
insert into counter_rows as cre1_0(id,next_value) values (?,?) on conflict(id) do update set next_value=(cre1_0.next_value+?) returning next_value,id
insert into counter_rows as cre1_0(id,next_value) values (?,?) on conflict(id) do update set next_value=(cre1_0.next_value+?) returning next_value,id
