insert into ins_arbiter as iae1_0(id,code,sku,status,name) values (?,?,?,?,?), (?,?,?,?,?) on conflict(sku) where status <> 'CLOSED' do update set name=excluded.name
