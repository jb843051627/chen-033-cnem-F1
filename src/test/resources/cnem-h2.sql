-- H2（MySQL 模式）测试库：只建作业生命周期涉及的表，列与 doc/schema/cnem.sql 实体逐列对齐
CREATE TABLE t_cnem_job (
  id bigint NOT NULL,
  job_no varchar(32),
  job_name varchar(64),
  status int,
  total_rows int,
  done_rows int,
  fail_rows int,
  eff_start datetime,
  eff_end datetime,
  version varchar(32),
  row_no int,
  insp_no varchar(64),
  amount decimal(14,2),
  job_qty int,
  version_status int,
  writeback_flag int,
  compensate_flag int,
  compensated int,
  compensate_cnt int,
  fail_reason varchar(255),
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  PRIMARY KEY (id),
  CONSTRAINT uk_job_head UNIQUE (job_no, row_no)
);

CREATE TABLE t_cnem_job_row (
  id bigint NOT NULL,
  job_no varchar(32),
  row_no int,
  insp_no varchar(64),
  item_code varchar(64),
  unit_name varchar(128),
  qty decimal(14,2),
  rule_id bigint,
  amount decimal(14,2),
  job_qty int,
  version varchar(32),
  version_status int,
  writeback_flag int,
  compensate_flag int,
  compensated int,
  fail_reason varchar(255),
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  PRIMARY KEY (id),
  CONSTRAINT uk_job_row UNIQUE (job_no, row_no)
);
CREATE INDEX idx_job_row_job ON t_cnem_job_row (job_no);

CREATE TABLE t_cnem_insp_rule (
  id bigint NOT NULL,
  rule_code varchar(32),
  rule_name varchar(64),
  th1_max decimal(14,2),
  th2_max decimal(14,2),
  th3_max decimal(14,2),
  eff_start datetime,
  eff_end datetime,
  priority int,
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  PRIMARY KEY (id)
);

CREATE TABLE t_cnem_insp_ledger (
  id bigint NOT NULL,
  job_no varchar(32) NOT NULL,
  row_no int NOT NULL,
  insp_no varchar(64),
  item_code varchar(64),
  unit_name varchar(128),
  grade_level int,
  rule_id bigint,
  version varchar(32),
  amount decimal(14,2),
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  PRIMARY KEY (id),
  CONSTRAINT uk_ledger_job_row UNIQUE (job_no, row_no)
);

CREATE TABLE t_cnem_fee_flow (
  id bigint NOT NULL,
  job_no varchar(32) NOT NULL,
  row_no int NOT NULL,
  insp_no varchar(64),
  item_no varchar(64),
  amount decimal(14,2),
  flow_type int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  PRIMARY KEY (id),
  CONSTRAINT uk_fee_flow_row UNIQUE (job_no, row_no, flow_type)
);

CREATE TABLE t_cnem_job_oplog (
  id bigint NOT NULL,
  job_no varchar(32) NOT NULL,
  row_no int,
  stage varchar(16),
  action varchar(32),
  from_status int,
  to_status int,
  operator varchar(64),
  op_time datetime,
  note varchar(500),
  del_flag int DEFAULT 0,
  PRIMARY KEY (id)
);
CREATE INDEX idx_oplog_job ON t_cnem_job_oplog (job_no, op_time, row_no);

-- 判据种子：1 启用；2 撤下；3 过期；4 未生效
INSERT INTO t_cnem_insp_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (1, 'IR01', '启用判据', 2000.00, 8000.00, 20000.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0),
       (2, 'IR02', '撤下判据', 2000.00, 8000.00, 20000.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 1, 0),
       (3, 'IR03', '过期判据', 2000.00, 8000.00, 20000.00, '2020-01-01 00:00:00', '2021-12-31 23:59:59', 10, 0, 0),
       (4, 'IR04', '未生效判据', 2000.00, 8000.00, 20000.00, '2090-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0);
