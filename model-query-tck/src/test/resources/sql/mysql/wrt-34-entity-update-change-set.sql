select ile1_0.id from ins_listened ile1_0 where ile1_0.id=?
select ile1_0.id,ile1_0.amount,ile1_0.note,ile1_0.source_id,ile1_0.status,ile1_0.version from ins_listened ile1_0 where ile1_0.id=?
update ins_listened set amount=?,note=?,source_id=?,status=?,version=? where id=? and version=?
