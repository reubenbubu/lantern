CREATE TABLE ${table_prefix}invoice (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  total DECIMAL(12, 2) NOT NULL,
  mysql_only INT
);
