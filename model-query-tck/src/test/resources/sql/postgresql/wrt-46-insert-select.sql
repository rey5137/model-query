insert into counter_rows as cre1_0(id,next_value) select oie1_0.id,nextval('tck_seq') from order_items oie1_0 where oie1_0.id in (?,?)
select cre1_0.id,cre1_0.next_value from counter_rows cre1_0 order by cre1_0.id
