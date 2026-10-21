select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.grp)=? order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.grp) like ? escape '\' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code)=? order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like ? escape '' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like ? escape '\' order by 1
