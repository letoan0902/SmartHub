-- Seed don hang mau, chay lai nhieu lan khong trung nho WHERE NOT EXISTS theo tracking_code
INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-001', 'Nguyễn Văn An', 'HN-01', 'IN_TRANSIT', 250000, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-001');

INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-002', 'Trần Thị Bích', 'SG-02', 'DELIVERED', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-002');

INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-003', 'Lê Minh Cường', 'HN-01', 'DELAYED', 1200000, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-003');

INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-004', 'Phạm Thu Dung', 'DN-03', 'IN_TRANSIT', 480000, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-004');

INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-005', 'Hoàng Văn Em', 'SG-02', 'DELAYED', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-005');

INSERT INTO deliveries (tracking_code, customer_name, hub_code, status, cod_amount, created_at)
SELECT 'RK-2026-006', 'Vũ Thị Phương', 'HN-01', 'DELIVERED', 350000, NOW()
WHERE NOT EXISTS (SELECT 1 FROM deliveries WHERE tracking_code = 'RK-2026-006');
