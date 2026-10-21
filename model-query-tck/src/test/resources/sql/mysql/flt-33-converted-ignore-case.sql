select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code)=lower(?) order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like lower(?) escape '' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like lower(?) escape '\\' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like lower(?) escape '\\' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like lower(?) escape '\\' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.code) like lower(?) escape '\\' order by 1
select clr1_0.id from converted_like_rows clr1_0 left join converted_like_rows p1_0 on p1_0.id=clr1_0.parent_id where lower(p1_0.code)=lower(?) order by 1
select clr1_0.id from converted_like_rows clr1_0 left join converted_like_rows p1_0 on p1_0.id=clr1_0.parent_id where lower(p1_0.code) like lower(?) escape '' order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.tag_code)=lower(?) order by 1
select clr1_0.id from converted_like_rows clr1_0 where lower(clr1_0.tag_code) like lower(?) escape '\\' order by 1
