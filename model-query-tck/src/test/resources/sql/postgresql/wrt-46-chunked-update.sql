insert into counter_rows as cre1_0(id,next_value) values (?,?), (?,?), (?,?), (?,?), (?,?), (?,?)
select cre1_0.id from counter_rows cre1_0 order by 1 fetch first ? rows only
update counter_rows cre1_0 set next_value=nextval('tck_seq') where cre1_0.id in (?,?)
select cre1_0.id from counter_rows cre1_0 where cre1_0.id>? order by 1 fetch first ? rows only
update counter_rows cre1_0 set next_value=nextval('tck_seq') where cre1_0.id in (?,?)
select cre1_0.id from counter_rows cre1_0 where cre1_0.id>? order by 1 fetch first ? rows only
update counter_rows cre1_0 set next_value=nextval('tck_seq') where cre1_0.id in (?,?)
select cre1_0.id from counter_rows cre1_0 where cre1_0.id>? order by 1 fetch first ? rows only
select cre1_0.id,cre1_0.next_value from counter_rows cre1_0 order by cre1_0.id
