select ile1_0.id from ins_listened ile1_0 join ins_source s1_0 on s1_0.id=ile1_0.source_id where s1_0.code=? and ile1_0.id<=? order by 1 limit ?
select ile1_0.id,ile1_0.amount,ile1_0.note,ile1_0.source_id,ile1_0.status,ile1_0.version from ins_listened ile1_0 where ile1_0.id in (?,?) and exists(select 1 from ins_listened ile2_0 join ins_source s2_0 on s2_0.id=ile2_0.source_id where ile2_0.id=ile1_0.id and s2_0.code=? and ile2_0.id<=?)
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
select ile1_0.id from ins_listened ile1_0 join ins_source s1_0 on s1_0.id=ile1_0.source_id where s1_0.code=? and ile1_0.id<=? and (ile1_0.id>?) order by 1 limit ?
select ile1_0.id,ile1_0.amount,ile1_0.note,ile1_0.source_id,ile1_0.status,ile1_0.version from ins_listened ile1_0 where ile1_0.id in (?,?) and exists(select 1 from ins_listened ile2_0 join ins_source s2_0 on s2_0.id=ile2_0.source_id where ile2_0.id=ile1_0.id and s2_0.code=? and ile2_0.id<=?)
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
select ile1_0.id from ins_listened ile1_0 join ins_source s1_0 on s1_0.id=ile1_0.source_id where s1_0.code=? and ile1_0.id<=? and (ile1_0.id>?) order by 1 limit ?
select ile1_0.id,ile1_0.amount,ile1_0.note,ile1_0.source_id,ile1_0.status,ile1_0.version from ins_listened ile1_0 where ile1_0.id=? and exists(select 1 from ins_listened ile2_0 join ins_source s2_0 on s2_0.id=ile2_0.source_id where ile2_0.id=ile1_0.id and s2_0.code=? and ile2_0.id<=?)
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
