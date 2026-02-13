-- Пароль: admin (BCrypt hash)
INSERT INTO users (username, password_hash, full_name, role, enabled, created_at)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Администратор', 'ADMIN', TRUE, NOW());
