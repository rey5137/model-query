select sole1_0.id,p1_0.id from sku_order_lines sole1_0 left join sku_products p1_0 on p1_0.sku=sole1_0.product_sku where sole1_0.id<=? order by 1
select fle1_0.id,p1_0.code from formula_lines fle1_0 left join formula_products p1_0 on p1_0.code=upper(fle1_0.product_code) where fle1_0.id<=? order by 1
