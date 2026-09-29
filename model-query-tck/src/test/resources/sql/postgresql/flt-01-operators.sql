select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status<>? or oe1_0.status is null order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total>? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total>=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total<? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total<=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.placed_at>=? and oe1_0.placed_at<? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.placed_at>=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.placed_at<? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total between ? and ? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total>=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total<=? order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status in (?,?) order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.status not in (?,?) or oe1_0.status is null order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.name like ? escape '\' order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.name like ? escape '\' order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.name like ? escape '\' order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.name like ? escape '' order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where lower(c1_0.name) like ? escape '\' order by 1
select oe1_0.id,oe1_0.status,oe1_0.total,oe1_0.placed_at,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where lower(oe1_0.status)=? order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id=o1_0.id order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id<>o1_0.id order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id<o1_0.id order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id<=o1_0.id order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id>o1_0.id order by 1
select oie1_0.id,o1_0.id from order_items oie1_0 join orders o1_0 on o1_0.id=oie1_0.order_id where oie1_0.id>=o1_0.id order by 1
select nse1_0.id,nse1_0.sort_int,nse1_0.sort_text,nse1_0.sort_ts from nullable_sort_rows nse1_0 where nse1_0.sort_int is null order by 1
select nse1_0.id,nse1_0.sort_int,nse1_0.sort_text,nse1_0.sort_ts from nullable_sort_rows nse1_0 where nse1_0.sort_int is not null order by 1
select nse1_0.id,nse1_0.sort_int,nse1_0.sort_text,nse1_0.sort_ts from nullable_sort_rows nse1_0 where nse1_0.sort_int<>? or nse1_0.sort_int is null order by 1
select nse1_0.id,nse1_0.sort_int,nse1_0.sort_text,nse1_0.sort_ts from nullable_sort_rows nse1_0 where nse1_0.sort_text not in (?,?) or nse1_0.sort_text is null order by 1
select nse1_0.id,nse1_0.sort_int,nse1_0.sort_text,nse1_0.sort_ts from nullable_sort_rows nse1_0 where nse1_0.sort_ts=? order by 1
