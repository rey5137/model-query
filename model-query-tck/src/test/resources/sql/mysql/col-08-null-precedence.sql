select nse1_0.id from nullable_sort_rows nse1_0 order by nse1_0.sort_int limit ?
select nse1_0.id from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end,nse1_0.sort_int limit ?
select nse1_0.id from nullable_sort_rows nse1_0 order by case when nse1_0.sort_int is null then ? else ? end desc,nse1_0.sort_int desc limit ?
