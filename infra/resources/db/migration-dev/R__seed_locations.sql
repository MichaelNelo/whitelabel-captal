-- Dev seed: Locations
DELETE FROM locations;

INSERT INTO locations (id, slug, name, is_active, created_at, updated_at, ap_mac) VALUES
('d0080000-0000-4000-8000-000000000001', 'cafe-centro', 'Cafe Centro', 1, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'AA:BB:CC:DD:EE:01'),
('d0080000-0000-4000-8000-000000000002', 'hotel-plaza', 'Hotel Plaza', 1, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', 'AA:BB:CC:DD:EE:02');
