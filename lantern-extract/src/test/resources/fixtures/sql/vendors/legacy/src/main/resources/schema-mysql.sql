# Schema for MySQL, as the client runs it
CREATE TABLE account (
  id INT PRIMARY KEY,
  balance DECIMAL(10, 2)
);

DELIMITER $$
CREATE TRIGGER account_check BEFORE UPDATE ON account
FOR EACH ROW
BEGIN
  IF NEW.balance < 0 THEN
    SET NEW.balance = 0;
  END IF;
END$$
DELIMITER ;

  # an indented comment
CREATE TABLE transfer (
  id INT PRIMARY KEY,
  account_id INT REFERENCES account (id)
);
