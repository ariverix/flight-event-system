-- Пароль: admin (BCrypt hash)
INSERT INTO users (username, password_hash, full_name, role, enabled, created_at)
VALUES ('admin', '$2a$10$E9lAn2MANmjcZfyBxCb5A.TX/9LmS2wEX9KkJMRggx05drmP/H656', 'Администратор', 'ADMIN', TRUE, NOW());
