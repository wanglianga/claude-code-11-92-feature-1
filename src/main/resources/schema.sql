-- ============================================================================
-- 港口靠泊协同服务 - PostgreSQL 结构（幂等，可重复执行）
-- 时间一律使用 timestamp（本地模拟时间，不带时区）
-- ============================================================================

-- ---------- 账号（轻量角色登录，测试账号由 DataSeeder 写入） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64) UNIQUE NOT NULL,
    password     VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_token (
    token      VARCHAR(80) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 基础模拟数据 ----------
CREATE TABLE IF NOT EXISTS ship (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(40) UNIQUE NOT NULL,
    name       VARCHAR(128) NOT NULL,
    length_m   NUMERIC(6,2) NOT NULL,   -- 船长
    draft_m    NUMERIC(5,2) NOT NULL,   -- 满载吃水
    cargo_type VARCHAR(32)  NOT NULL    -- CONTAINER/BULK/TANKER/RORO/GENERAL
);

CREATE TABLE IF NOT EXISTS voyage (
    id                BIGSERIAL PRIMARY KEY,
    voyage_no         VARCHAR(40) UNIQUE NOT NULL,
    ship_id           BIGINT NOT NULL REFERENCES ship(id),
    agent_name        VARCHAR(128),
    eta_start         TIMESTAMP NOT NULL,
    eta_end           TIMESTAMP NOT NULL,
    work_duration_min INT NOT NULL,     -- 装卸作业时长（分钟）
    crane_count       INT NOT NULL,     -- 所需岸桥数
    status            VARCHAR(32) NOT NULL, -- SUBMITTED/ETA_UPDATED/WITHDRAWN/CONFIRMED
    eta_revision      INT NOT NULL DEFAULT 0,
    remark            TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (eta_end >= eta_start)
);
CREATE INDEX IF NOT EXISTS idx_voyage_status ON voyage(status);

CREATE TABLE IF NOT EXISTS berth (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    max_length_m   NUMERIC(6,2) NOT NULL,  -- 可容船长
    chart_depth_m  NUMERIC(5,2) NOT NULL,  -- 图示水深（理论基准面下）
    ukc_min_m      NUMERIC(4,2) NOT NULL DEFAULT 1.0 -- 最小吃水下净空
);

-- 泊位允许货类（显式名单）
CREATE TABLE IF NOT EXISTS berth_cargo_limit (
    berth_id   BIGINT NOT NULL REFERENCES berth(id) ON DELETE CASCADE,
    cargo_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (berth_id, cargo_type)
);

-- 潮汐时段水位：slot [slot_start, slot_end) 内代表水位 height_m
CREATE TABLE IF NOT EXISTS tide_slot (
    id            BIGSERIAL PRIMARY KEY,
    slot_start    TIMESTAMP NOT NULL UNIQUE,
    slot_end      TIMESTAMP NOT NULL,
    height_m      NUMERIC(5,2) NOT NULL,
    revised       BOOLEAN NOT NULL DEFAULT FALSE,
    revision_reason TEXT,
    CHECK (slot_end > slot_start)
);

CREATE TABLE IF NOT EXISTS pilot (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    qualification  VARCHAR(40) NOT NULL,   -- DEEP / COASTAL
    max_length_m   NUMERIC(6,2) NOT NULL,
    max_draft_m    NUMERIC(5,2) NOT NULL,
    handover_min   INT NOT NULL DEFAULT 30 -- 交接时间
);

CREATE TABLE IF NOT EXISTS tug (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40) UNIQUE NOT NULL,
    name             VARCHAR(128) NOT NULL,
    max_length_m     NUMERIC(6,2) NOT NULL,
    max_draft_m      NUMERIC(5,2) NOT NULL,
    bollard_pull_t   NUMERIC(6,1),
    turnaround_min   INT NOT NULL DEFAULT 30 -- 周转时间
);

CREATE TABLE IF NOT EXISTS quay_crane (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    transfer_min   INT NOT NULL DEFAULT 20, -- 转场时间
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

-- 人员/设备的工作区间（同一资源可有多段班次）
CREATE TABLE IF NOT EXISTS work_window (
    id            BIGSERIAL PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,  -- PILOT/TUG/CRANE
    resource_id   BIGINT NOT NULL,
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    CHECK (end_at > start_at)
);
CREATE INDEX IF NOT EXISTS idx_work_window_res
    ON work_window(resource_type, resource_id);

-- 模拟外部设备状态：故障/停用窗口
CREATE TABLE IF NOT EXISTS equipment_fault (
    id            BIGSERIAL PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,  -- CRANE/TUG/PILOT
    resource_id   BIGINT NOT NULL,
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fault_res
    ON equipment_fault(resource_type, resource_id, start_at, end_at);

-- ---------- 方案与分段 ----------
CREATE TABLE IF NOT EXISTS berth_plan (
    id               BIGSERIAL PRIMARY KEY,
    plan_no          VARCHAR(48) UNIQUE NOT NULL,
    batch_id         VARCHAR(40),
    voyage_id        BIGINT NOT NULL REFERENCES voyage(id),
    berth_id         BIGINT NOT NULL REFERENCES berth(id),
    status           VARCHAR(24) NOT NULL,
        -- CANDIDATE / STAGED / CONFIRMED / EXPIRED / CANCELLED / SUPERSEDED / REPLANNED
    feasible         BOOLEAN NOT NULL DEFAULT TRUE,
    strategy         VARCHAR(24),         -- TENTATIVE_HOLD / ATOMIC_CONFIRM
    -- 关键时间轴
    berth_from       TIMESTAMP,           -- S：靠泊/靠泊操纵开始
    pilot_start      TIMESTAMP,
    ops_start        TIMESTAMP,
    ops_end          TIMESTAMP,
    berth_to         TIMESTAMP,
    score            INT NOT NULL DEFAULT 0,
    wait_minutes     INT NOT NULL DEFAULT 0,
    reasons_json     TEXT,                -- 候选不可行原因 / 等待原因
    assignments_json TEXT,                -- 资源分配快照
    hold_expires_at  TIMESTAMP,
    staged_at        TIMESTAMP,
    confirmed_at     TIMESTAMP,
    released_at      TIMESTAMP,
    release_reason   VARCHAR(40),
    version_no       INT NOT NULL DEFAULT 1,
    replan_of_id     BIGINT,
    disruption_id    BIGINT,
    impact_json      TEXT,
    note             TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plan_voyage ON berth_plan(voyage_id);
CREATE INDEX IF NOT EXISTS idx_plan_status ON berth_plan(status);
CREATE INDEX IF NOT EXISTS idx_plan_batch ON berth_plan(batch_id);
-- 同一船次至多一个 CONFIRMED 方案（唯一确认结果）
CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_one_confirmed
    ON berth_plan(voyage_id) WHERE status = 'CONFIRMED';

CREATE TABLE IF NOT EXISTS plan_segment (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       BIGINT NOT NULL REFERENCES berth_plan(id) ON DELETE CASCADE,
    seq_no        INT NOT NULL,
    seg_type      VARCHAR(24) NOT NULL,
        -- PILOTAGE/TUG_INBOUND/BERTHING/CARGO_OP/TUG_OUTBOUND/UNBERTHING
    title         VARCHAR(80) NOT NULL,
    resource_type VARCHAR(16),
    resource_id   BIGINT,
    resource_code VARCHAR(40),
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
        -- PLANNED/IN_PROGRESS/DONE/CANCELLED
    affected      BOOLEAN NOT NULL DEFAULT FALSE,
    affect_reason TEXT,
    record_id     BIGINT,
    CHECK (end_at >= start_at)
);
CREATE INDEX IF NOT EXISTS idx_segment_plan ON plan_segment(plan_id);
CREATE INDEX IF NOT EXISTS idx_segment_resource
    ON plan_segment(resource_type, resource_id, start_at, end_at);

-- 已完成作业记录（引航 / 装卸）：只增不改不删，新计划只能引用与延续
CREATE TABLE IF NOT EXISTS work_record (
    id              BIGSERIAL PRIMARY KEY,
    voyage_id       BIGINT NOT NULL,
    plan_id         BIGINT NOT NULL,
    segment_id      BIGINT,
    record_type     VARCHAR(24) NOT NULL,  -- PILOTAGE / CARGO_HANDLING
    operator_name   VARCHAR(128),
    work_minutes    INT NOT NULL DEFAULT 0,-- 本次完成的作业分钟数
    moved_units     INT NOT NULL DEFAULT 0,
    result_note     TEXT,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_record_voyage ON work_record(voyage_id);

-- 作业记录不可变触发器、资源占用互斥触发器由 DbTriggerInitializer 以
-- JdbcTemplate 方式创建（避免脚本切分器与 plpgsql 美元引用冲突）。

-- ---------- 资源占用（暂留 TENTATIVE / 确认 FIRM 统一建模） ----------
-- 每种“可占用资源”一个锁父行，触发器内对该行 FOR UPDATE，
-- 把同一资源的并发占用串行化，再做区间重叠检查。
CREATE TABLE IF NOT EXISTS resource_lock (
    resource_key VARCHAR(80) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS resource_occupation (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        BIGINT NOT NULL,
    voyage_id      BIGINT NOT NULL,
    resource_type  VARCHAR(16) NOT NULL,   -- BERTH/PILOT/TUG/CRANE
    resource_id    BIGINT NOT NULL,
    resource_key   VARCHAR(80) NOT NULL,
    start_at       TIMESTAMP NOT NULL,     -- 已包含准备/交接/转场/周转缓冲
    end_at         TIMESTAMP NOT NULL,
    kind           VARCHAR(16) NOT NULL,   -- TENTATIVE / FIRM
    status         VARCHAR(16) NOT NULL,   -- ACTIVE / RELEASED
    released_at    TIMESTAMP,
    release_reason VARCHAR(40),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (end_at > start_at)
);
CREATE INDEX IF NOT EXISTS idx_occ_active_lookup
    ON resource_occupation(resource_type, resource_id, start_at, end_at)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_occ_plan ON resource_occupation(plan_id);

-- 占用互斥触发器 fn_occupation_overlap_guard / trg_occupation_overlap
-- 同样由 DbTriggerInitializer 创建。

-- ---------- 事件 / 扰动 / 影响 ----------
CREATE TABLE IF NOT EXISTS plan_event (
    id          BIGSERIAL PRIMARY KEY,
    voyage_id   BIGINT,
    plan_id     BIGINT,
    event_type  VARCHAR(48) NOT NULL,
    message     TEXT,
    detail_json TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_voyage ON plan_event(voyage_id);

CREATE TABLE IF NOT EXISTS disruption (
    id           BIGSERIAL PRIMARY KEY,
    dtype        VARCHAR(24) NOT NULL,    -- TIDE_REVISION / CRANE_FAULT
    ref_id       BIGINT,
    payload_json TEXT,
    summary      TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS impact_item (
    id          BIGSERIAL PRIMARY KEY,
    disruption_id BIGINT REFERENCES disruption(id),
    plan_id     BIGINT,
    voyage_id   BIGINT,
    segment_id  BIGINT,
    severity    VARCHAR(16),             -- BLOCKER/WARNING
    code        VARCHAR(40),
    message     TEXT,
    start_at    TIMESTAMP,
    end_at      TIMESTAMP,
    handled     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_impact_disruption ON impact_item(disruption_id);

-- ============================================================================
-- 舱口顺序 / 纵向力矩 / 岸桥禁入 工作台（本题新增）
-- 计划值与实测值严格分离：hatch_record 为只增台账（卸货实测、压载计划指令、
-- 压载实测回执），重排只引用实测，未到回执的压载计划不得冒充实测。
-- ============================================================================

CREATE TABLE IF NOT EXISTS hatch_case (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(48) UNIQUE NOT NULL,
    name        VARCHAR(160),
    voyage_id   BIGINT,
    strategy    VARCHAR(24) NOT NULL DEFAULT 'PARALLEL_SIDES', -- HATCH_ORDER / PARALLEL_SIDES
    config_json TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hatch_plan (
    id              BIGSERIAL PRIMARY KEY,
    case_id         BIGINT NOT NULL REFERENCES hatch_case(id) ON DELETE CASCADE,
    version_no      INT NOT NULL,
    basis           VARCHAR(32) NOT NULL,  -- INITIAL_PLAN / REPLANNED_FROM_ACTUALS
    strategy        VARCHAR(24) NOT NULL,
    summary_json    TEXT,
    plan_json       TEXT NOT NULL,
    provenance_json TEXT,                  -- 重排时“哪些实测被采用/哪些待回执计划被排除”
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hatch_plan_case ON hatch_plan(case_id, version_no);

-- 只增台账：CARGO_ACTUAL（卸货实测）/ BALLAST_ORDER（压载计划指令）/ BALLAST_RECEIPT（压载实测回执）
CREATE TABLE IF NOT EXISTS hatch_record (
    id            BIGSERIAL PRIMARY KEY,
    case_id       BIGINT NOT NULL REFERENCES hatch_case(id) ON DELETE CASCADE,
    rec_type      VARCHAR(24) NOT NULL,
    order_code    VARCHAR(48),             -- 压载指令与其回执的关联码
    hatch_code    VARCHAR(40),
    tank_code     VARCHAR(40),
    delta_t       DOUBLE PRECISION,        -- CARGO_ACTUAL：本次实测卸货增量
    actual_t      DOUBLE PRECISION,        -- CARGO_ACTUAL：累计实测；RECEIPT：回执实测增减
    planned_t     DOUBLE PRECISION,        -- BALLAST_ORDER：计划增减
    late          BOOLEAN NOT NULL DEFAULT FALSE, -- 回执是否迟到
    operator_name VARCHAR(128),
    note          TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hatch_record_case ON hatch_record(case_id, id);

