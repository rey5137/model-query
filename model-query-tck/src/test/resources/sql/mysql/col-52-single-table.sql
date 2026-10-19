select sp1_0.status,sp1_0.number,sp1_0.id from AnchorStPayment sp1_0 limit ?
select count(sp1_0.id) from AnchorStPayment sp1_0
select count(sp1_0.id) from AnchorStPayment sp1_0
select sp1_0.id,sp1_0.status,sp1_0.number from AnchorStPayment sp1_0 order by 3,1 limit ?,?
select count(sp1_0.id) from AnchorStPayment sp1_0 where sp1_0.number is null
select sp1_0.status,sp1_0.id from AnchorStPayment sp1_0 where sp1_0.number is null limit ?
select count(sp1_0.id) from AnchorStPayment sp1_0 where sp1_0.number<>? or sp1_0.number is null
select sp1_0.status,sp1_0.id from AnchorStPayment sp1_0 where sp1_0.number<>? or sp1_0.number is null limit ?
select count(sp1_0.id) from AnchorStPayment sp1_0 where sp1_0.number not in (?) or sp1_0.number is null
select sp1_0.status,sp1_0.id from AnchorStPayment sp1_0 where sp1_0.number not in (?) or sp1_0.number is null limit ?
select count(sp1_0.id) from AnchorStPayment sp1_0 where sp1_0.number is null
select sp1_0.status,sp1_0.id from AnchorStPayment sp1_0 where sp1_0.number is null limit ?
