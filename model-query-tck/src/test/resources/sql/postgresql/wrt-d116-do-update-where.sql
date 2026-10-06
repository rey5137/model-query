insert into ins_assigned as iae1_0(id,code,name,version) values (1,'c1','Y1',?), (2,'c2','Y2',?) on conflict(id) do update set name=excluded.name where iae1_0.name='N2'
