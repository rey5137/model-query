select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for no key update
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for no key update nowait
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for no key update skip locked
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for share
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for share nowait
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 for share skip locked
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for no key update
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for no key update nowait
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for no key update skip locked
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for share
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for share nowait
select ce1_0.id,ce1_0.country,ce1_0.created_at,ce1_0.email,ce1_0.name,ce1_0.vip from customers ce1_0 join orders o1_0 on ce1_0.id=o1_0.customer_id for share skip locked
