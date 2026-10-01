select ckie1_0.tenant_id,ckie1_0.item_no from composite_key_items ckie1_0 where ckie1_0.tenant_id=? and ckie1_0.item_no<? order by 1,2 limit ?
update composite_key_items ckie1_0 set label=? where (ckie1_0.tenant_id=? and ckie1_0.item_no=? or ckie1_0.tenant_id=? and ckie1_0.item_no=? or ckie1_0.tenant_id=? and ckie1_0.item_no=?) and ckie1_0.tenant_id=? and ckie1_0.item_no<?
select ckie1_0.tenant_id,ckie1_0.item_no from composite_key_items ckie1_0 where ckie1_0.tenant_id=? and ckie1_0.item_no<? and (ckie1_0.tenant_id>? or ckie1_0.tenant_id=? and ckie1_0.item_no>?) order by 1,2 limit ?
update composite_key_items ckie1_0 set label=? where (ckie1_0.tenant_id=? and ckie1_0.item_no=? or ckie1_0.tenant_id=? and ckie1_0.item_no=? or ckie1_0.tenant_id=? and ckie1_0.item_no=?) and ckie1_0.tenant_id=? and ckie1_0.item_no<?
select ckie1_0.tenant_id,ckie1_0.item_no from composite_key_items ckie1_0 where ckie1_0.tenant_id=? and ckie1_0.item_no<? and (ckie1_0.tenant_id>? or ckie1_0.tenant_id=? and ckie1_0.item_no>?) order by 1,2 limit ?
update composite_key_items ckie1_0 set label=? where ckie1_0.tenant_id=? and ckie1_0.item_no=? and ckie1_0.tenant_id=? and ckie1_0.item_no<?
