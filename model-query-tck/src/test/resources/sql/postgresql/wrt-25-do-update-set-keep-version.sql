insert into ins_assigned as iae1_0(id,code,name,version) values (?,?,?,?), (?,?,?,?) on conflict(code) do update set name=?
