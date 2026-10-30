CREATE TABLE report (
  id INT PRIMARY KEY,
  title NVARCHAR(100)
)
GO

CREATE TABLE report_run (
  id INT PRIMARY KEY,
  report_id INT
)
/

ALTER TABLE report_run ADD CONSTRAINT report_run_report_fk FOREIGN KEY (report_id) REFERENCES report (id)
GO
