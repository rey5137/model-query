insert into ins_arbiter as iae1_0(id,code,sku,status,name) values (?,?,?,?,?), (?,?,?,?,?) on conflict on constraint ins_arbiter_code_key do nothing
