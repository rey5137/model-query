select jp1_0.status,jp1_1.number,jp1_0.id from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id fetch first ? rows only
select count(jp1_0.id) from AnchorJdPayment jp1_0
select count(jp1_0.id) from AnchorJdPayment jp1_0
select jp1_0.id,jp1_0.status,jp1_1.number from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id order by 3,1 offset ? rows fetch first ? rows only
select count(jp1_0.id) from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number is null
select jp1_0.status,jp1_0.id from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number is null fetch first ? rows only
select count(jp1_0.id) from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number<>? or jp1_1.number is null
select jp1_0.status,jp1_0.id from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number<>? or jp1_1.number is null fetch first ? rows only
select count(jp1_0.id) from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number not in (?) or jp1_1.number is null
select jp1_0.status,jp1_0.id from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number not in (?) or jp1_1.number is null fetch first ? rows only
select count(jp1_0.id) from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number is null
select jp1_0.status,jp1_0.id from AnchorJdPayment jp1_0 left join AnchorJdCard jp1_1 on jp1_0.id=jp1_1.id where jp1_1.number is null fetch first ? rows only
