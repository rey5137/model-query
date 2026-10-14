select id, referrer_id from final table (update orders oe1_0 set status=?,version=(oe1_0.version+cast(? as integer)) where oe1_0.id<?)
