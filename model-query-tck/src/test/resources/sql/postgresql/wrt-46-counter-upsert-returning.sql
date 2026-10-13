insert into counter_rows as cre1_0(id,next_value) values (?,nextval('tck_seq')) on conflict(id) do update set next_value=(cre1_0.next_value+?) returning next_value,id
insert into counter_rows as cre1_0(id,next_value) values (?,nextval('tck_seq')) on conflict(id) do update set next_value=(cre1_0.next_value+?) returning next_value,id
