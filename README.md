# 港口靠泊协同服务（Berth Coordination）

船代提交船次（船长/吃水/货类/ETA 区间/作业时长/岸桥数），码头调度枚举并比较候选靠泊计划，
对 **泊位 + 引航员 + 拖轮 + 岸桥整组资源** 做暂留或原子确认；确认后的计划才下发引航、拖轮、岸桥班组。
系统在**整个靠泊操纵与装卸窗口**校验吃水下净空（UKC ≥ 1m）、货类限制、人员值班/交接、
设备工作区间/转场/周转，并提供潮汐修订、岸桥故障、改靠、ETA 迟到的影响分析与版本化重排；
已完成的引航/装卸记录在应用层与数据库触发器双层保证不可覆盖。

- 后端：Java（`--release 21`，已在预装 **JDK 25.0.1** 上构建运行）、Spring Boot 3.5.7、Spring JDBC、PostgreSQL
- 前端：`src/main/resources/static/` 原生 HTML/CSS/JS 单页（无需 Node 构建）
- 接口文档：springdoc-openapi，`/swagger-ui.html`
- 一键启动：`Dockerfile` + `docker-compose.yml`（PostgreSQL 18）

> 🆕 **本题新增「⑥ 舱口顺序 · 纵倾力矩 · 岸桥交叉工作台」**：给定舱位位置/各舱货重/压载调整能力/
> 岸桥禁入组合，生成带 1 小时时间片的作业序列，对比 **舱序优先** 与 **岸桥并行** 两种排法的
> 首小时安排、总时长与被拒步骤；并把**实测完成量**与**压载实测回执**用于后续重排——
> **压载回执迟到/未到时，计划值绝不会被当作实测**。样例数值、API、界面与验证见
> [第 11 节](#11-舱口顺序--纵向力矩--岸桥交叉工作台本次新增)。

---

## 1. 题目场景与内置样例

固定样例时间为 **2026-11-26 晚**，重置接口/回放会反复恢复该数据：

- **B1 深水泊位**：可容船长 320m、图示水深 9.5m；深水船吃水 11.5m、UKC 要求 1m
  ⇒ 需要潮位 `h ≥ 11.5 + 1.0 − 9.5 = 3.0m`。
- 30 分钟一档的模拟潮汐中，**只有 S=20:00 这一个 15 分钟网格起点**能通过全窗口 UKC 校验
  （19:30 档潮位 2.8m 不够；21:30 档回落到 2.75m，20:15/20:30 起点也失败）。
- 该时段唯一有 DEEP 资质且值班的引航员 **PILOT-1 张远航**（值班 17:00–次日 02:00，交接 30m），
  唯一适配 300m/11.5m 船舶的拖轮 **TUG-1 港拖16**（周转 30m）；岸桥 QC-1~QC-4。
- **VOY-A 远集 A**：ETA `19:30–20:30`，作业 60m，需 2 台岸桥。
- **VOY-B 远集 B**：ETA `19:00–20:00`，已提交 20:00 靠泊，与 A 竞争同一组合。
- 另有 VOY-C/D/E 覆盖集装箱、滚装与“ETA 18–19 改 19–20”的开场故事。
- 次日 08:00 前后还有早潮窗口与 PILOT-3/TUG-3 早班，用于“等潮/等资源”的备选与重排。

---

## 2. 核心设计决定：何时占用整组靠泊资源

两种策略都已实现，并可用接口参数或回放对照：

| 策略 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **`TENTATIVE_HOLD`（默认）** | 调度“暂存”候选时，即在同一事务写入泊位/引航/拖轮/岸桥的 `TENTATIVE` 占用，TTL 默认 120s；到期由 500ms 扫描器自动释放 | 确认后资源不会被抢 | ETA 尚宽时资源可能空等 → 用短 TTL + 一键取消控制 |
| **`ATOMIC_CONFIRM`** | 暂存只保存方案、**不占资源**；确认时在一个事务内重校全部约束并插入 `FIRM` 占用 | 资源周转率高 | 已讨论方案可能在最后一步被别人锁走，确认必须原子重校+锁定 |

暂存接口可用 `"holdResources": false/true` 临时选择策略；`app.planning.hold-on-stage` 改全局默认。

**唯一性保证（不依赖应用锁）**：所有占用统一落在 `resource_occupation` 表。
PostgreSQL 触发器 `trg_occupation_overlap` 在写入前对 `resource_lock` 中该资源的锁父行 `SELECT … FOR UPDATE`，
再检查半开区间重叠；并发确认同一组合时恰好一个事务成功，另一个收到 `23P01/RESOURCE_OVERLAP` → HTTP 409。
另有部分唯一索引保证同一船次至多一个 `CONFIRMED` 方案。

---

## 3. 约束与时间轴模型

设 `S = berth_from`（靠泊操纵开始）：

```
引航段    [S-45, S+15]            占用区间前后各加 handover_min 交接缓冲
拖轮靠泊  [S-15, S+30]            占用尾部加 turnaround_min 周转
靠泊操纵  [S, S+30]               泊位占用开始
装卸作业  [S+30, S+30+duration]   每台岸桥占用至 opsEnd + transfer_min 转场
拖轮离泊  [opsEnd, opsEnd+30]     同样加周转缓冲
离泊段    [opsEnd, opsEnd+30]     泊位占用结束 = opsEnd+30
```

- **UKC**：对 `[S, opsEnd]` 与每个重叠潮汐档校验 `chart_depth + tide_height − draft ≥ ukc_min(=1.0)`，并检查潮汐覆盖完整性。
- 泊位：船长 ≤ `max_length_m`，货类必须在 `berth_cargo_limit` 名单内。
- 引航员：船长/吃水/资质（深水大船需 `DEEP`）+ 占用区间（含交接）完整落在某个 `work_window`。
- 拖轮：船长/吃水适配 + 靠/离两区间（含周转）均不冲突、在班。
- 岸桥：需要 N 台不同岸桥，每台工作窗覆盖 `[opsStart, opsEnd+transfer]`，且无占用/故障重叠。
- 候选生成：15 分钟网格，从 `etaStart` 枚举到 `etaEnd + 960m`；ETA 窗口内不可行点全部保留并给出
  结构化原因（`UKC_VIOLATION`、`*_HELD_TENTATIVE`、`*_BOOKED_FIRM`、`PILOT_OFF_DUTY`、
  `INSUFFICIENT_CRANES` 等，含占用方船次/方案/暂留到期时间），可行点按时间先后取前 6。

---

## 4. 目录结构

```
pom.xml / Dockerfile / docker-compose.yml
scripts/replay.sh                 # 命令行一键验证（登录 + /api/replays/full + 断言）
src/main/resources/
  application.yml                 # DB 环境变量、TTL、候选网格配置
  schema.sql                      # 表/索引（幂等）；触发器由 Java 下发
  static/                         # 单页前端 index.html / app.js / styles.css
src/main/java/com/port/berth/
  PortBerthApplication.java
  config/                         # 鉴权拦截器、CORS、OpenAPI、PG 触发器初始化
  domain/                         # 枚举、Catalog 记录、Eval、ConstraintMath
  repo/                           # CatalogDao / PlanDao（JdbcTemplate + 行映射）
  service/                        # PlanningEngine / PlanService / HoldSweeper
                                  # ExecutionService / DisruptionService / ReplanService
                                  # ReplayService / SeedService / AuthService
  hatch/                          # 【新增】舱口/纵倾/岸桥禁入：HatchConfig、HatchPlanner
                                  # HatchActuals（计划≠实测）、HatchService（持久化/重排/自验）
  web/                            # REST 控制器、全局异常处理（含 HatchController）
src/test/java/.../                # 13 个单元测试：约束数学 + 样例窗口 + 9 个舱口规划/实测用例
```

---

## 5. 一键启动

### 5.1 Docker Compose（推荐，干净环境）

```bash
cd /workspace/92-feature-1
docker compose up --build
```

- 应用：<http://localhost:8080>（单页操作界面）
- Swagger UI：<http://localhost:8080/swagger-ui.html>，OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL 18：容器内 `db:5432`，宿主机映射 **5433**（避免与本机 5432 冲突），
  库/用户/密码 `berth/berth/berth123`，数据卷 `pgdata`
- 镜像构建内已配置 `docker/settings-docker.xml` 走阿里云 Maven 源

> 本任务容器内没有安装 Docker CLI/守护进程，因此镜像构建与 `docker compose up` 未在本会话实际执行；
> `docker-compose.yml` 已通过 YAML 解析校验，Dockerfile 为标准多阶段构建。应用本身已用等价方式
> （JDK 25 + 可执行 jar + 真实 PostgreSQL 18）完整验证，见第 7 节。

### 5.2 本地开发（使用任务容器预装运行时）

PostgreSQL 18 已在 `127.0.0.1:5432` 运行，开发库/账号 `ccdev/ccdev`：

```bash
# 连通性检查
/opt/postgresql-18/bin/psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1'

# 若数据库进程未运行，可尝试：
# /opt/postgresql-18/bin/pg_ctl -D /tmp/cc-services/postgres -l /tmp/pg.log start

cd /workspace/92-feature-1
export JAVA_HOME=/opt/jdk-25
mvn spring-boot:run
# 或：mvn package && /opt/jdk-25/bin/java -jar target/berth-coordination.jar
```

零额外配置即可启动：默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev`（`ccdev/ccdev`），
首次启动自动执行 `schema.sql`、创建两个 PL/pgSQL 触发器并播种样例数据（幂等）。
也可用环境变量覆盖：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/PORT/HOLD_ON_STAGE/HOLD_TTL_SECONDS`。

---

## 6. 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `agent` | `agent123` | AGENT 船代 | 提交船次、补报 ETA、撤回授权 |
| `dispatch` | `dispatch123` | DISPATCHER 调度 | 生成候选、暂存/确认/取消、扰动录入、改靠/重排、回放、重置 |
| `pilot` | `pilot123` | TEAM 引航班组 | 看板、开工/完成/登记作业 |
| `tug` | `tug123` | TEAM 拖轮班组 | 同上 |
| `crane` | `crane123` | TEAM 岸桥班组 | 同上（含部分装卸量登记） |
| `admin` | `admin123` | ADMIN | 全部权限 |

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

除 `POST /api/auth/login` 与 `GET /api/meta` 外，`/api/**` 都需要
`Authorization: Bearer <token>`；角色不足返回 403。Swagger 中点 **Authorize** 填 token。

---

## 7. 本题验证方式与实际验证结论

### 7.1 自动化验证（本会话实际执行）

```bash
mvn test
# Tests run: 13, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS
#   ConstraintMathTest 3 + ScenarioWindowTest 1（既有，无回归）
#   hatch.HatchPlannerTest 9（新增：首小时/总时长/被拒步骤/纵倾/压载迟到不冒充实测）

mvn clean package
# 生成 target/berth-coordination.jar（约 28 MB，Spring Boot fat jar）
```

```bash
# 启动 jar 后执行（默认 http://127.0.0.1:8080）
bash scripts/replay.sh
# ==> 断言：46/46 通过；allPassed=True
# ALL_REPLAY_CHECKS_PASSED

# 本题新增特性的一键验证（内置 15 项服务级断言 + 13 项真实 HTTP 断言）
bash scripts/hatch-verify.sh
# ==> POST /api/hatch/verify -> 200，allPassed=True，15/15
# HATCH_HTTP_FLOW_PASSED
# ALL_HATCH_CHECKS_PASSED
```

> 上述 `mvn test`、`mvn clean package`、两个脚本（`replay.sh` 46/46、`hatch-verify.sh` 全过）
> 以及全新空库 8081 端口首启 + `hatch-verify.sh` 均在本次开发中**真实执行并通过**；
> 另用 Node 22 对真实 `/api/hatch/sample`、案例 JSON 做了前端渲染冒烟（模板不抛错、关键数值/按钮就位）。
> 工作区文本文件已统一为 LF 行尾，`bash scripts/replay.sh` 可直接运行（物化时曾是 CRLF）。

该脚本真实登录、调用 `POST /api/replays/full`（约 12 秒，含真实 2s TTL 等待、
500ms 扫描释放与双线程并发确认竞速），递归统计全部断言并以退出码反映结果。

此外实际执行并通过的验证：

- **全新空库验证**：另建空数据库、以 `DB_NAME=... PORT=8081 java -jar` 启动，
  建表/触发器/播种一次成功，回放同样全部通过（验证了 Compose 首次启动的初始化路径）。
- **HTTP 权限/业务冒烟 8/8**：匿名 401、公开 meta、船代提交船次、船代暂存 403、
  VOY-E ETA 补报状态变 `ETA_UPDATED`、TEAM 看板 200、错误密码 400、OpenAPI 200。
- 前端 `node --check app.js` 通过；所有按钮处理函数均有定义；`/`、`/app.js`、`/styles.css`、
  `/swagger-ui/index.html` 均实际返回 200。
- 回放中包含**数据库层自证不可变**：对 `work_record` 直接 `UPDATE/DELETE` 被触发器拒绝。

### 7.2 界面手工验证路径

1. 打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录（登录页可点账号芯片填充）。
2. **② 调度工作台** → 选 `VOY-A` → “生成/刷新候选计划”：可见唯一绿色可行卡
   `B1 20:00`，分配 `PILOT-1/TUG-1/QC-1/QC-2`；其余红色卡给出 UKC/值班等原因。
   - 设 TTL=2 秒点“暂存并暂留资源”，卡片变 `STAGED` 并显示到期时间；不确认，数秒后刷新可见 `EXPIRED`。
   - 对 `VOY-B` 生成候选，可见 20:00 卡被 A 的暂留/确认阻挡及结构化等待原因。
   - 可试“仅暂存不占资源”，再让 B 先确认，最后 A “直接原子确认”会收到 409。
3. **③ 班组看板**：只有 `CONFIRMED` 方案出现；可开工、登记部分作业量、完成分段。
4. **④ 模拟与扰动**：修订 21:00 潮位为 `2.20`、插入 QC-2 故障窗口、查看影响项；
   回到调度页对确认方案“扰动后重排”，或用确认卡上的“改靠/改时”。
5. **⑤ 一键回放验证**：三个按钮分别运行顺序集/扰动集/完整集，页面渲染每条断言与完整 JSON。
6. **① 船代申报**：可新建船次、对 VOY-E 把 ETA 18–19 补报为 19–20、撤回授权。

---

## 8. 一键回放到底核对了什么

`POST /api/replays/orderings`、`/api/replays/disruptions`、`/api/replays/full`
（DISPATCHER/ADMIN）每次先重置固定样例，再输出带断言与完整时间线的报告：

| 回放块 | 核对点（全部 PASS） |
| --- | --- |
| **顺序1：A 暂存后未确认** | A 的 B1 20:00 候选可行且选到 P1/T1/QC1/QC2；A `STAGED`；B 同时点不可行且原因含 `*_HELD_TENTATIVE`；B 强确认 409；TTL 到期 A `EXPIRED`、≥5 条占用全 `RELEASED`、扫描延迟 <3s；A 确认数 0；释放后 B 可行并成为唯一 `CONFIRMED` |
| **顺序2：A 暂留期内确认** | A `CONFIRMED` 且唯一；B 被 `*_BOOKED_FIRM` 阻挡；B 暂存/确认均 409；B 得到等待 >120m 的次日备选；B 确认数 0 |
| **顺序3：B 先确认** | B 直接原子确认成功；A 候选显示 FIRM 阻挡；A 暂存、最后一步确认均 409；确认计数 B=1/A=0 |
| **并发竞速** | 两线程同时确认 A/B 同一组合：恰好 1 个成功，库中 A/B 合计仅 1 个 `CONFIRMED`（触发器+资源锁行兜底） |
| **策略对照** | `holdResources=false` 时暂存零占用；B 先确认；A 事后确认 409（“最后一步失效”），并附两种策略权衡说明 |
| **潮汐修订** | 先写 1 条引航完成记录 + 1 条 15 分钟装卸台账；21:00 潮位 3.2→2.2 后分析出指向装卸段的 `UKC_VIOLATION`；重排模式 `RESCHEDULE`、次日 08:00 新方案 `CONFIRMED`、只排剩余 45m；2 条记录保留；DB 阻止 UPDATE/DELETE |
| **岸桥故障** | QC-2 20:45–22:30 故障生成 `CRANE_FAULT`；重排为 `SAME_SLOT_REASSIGN`，新分配含 QC-3 不含 QC-2；引航记录保留 |
| **改靠指令** | 对 VOY-D 下达改靠 B2↔B3：新版本确认且泊位变化；≥4 条 `BERTH_CHANGE` 逐段影响说明 |
| **ETA 迟到** | ETA 最早时间晚于计划靠泊点时生成 ≥4 条 `ETA_LATE`（引航/拖轮/靠泊/装卸），随后重排出 `CONFIRMED v2` |

---

## 9. 主要 HTTP API

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | 登录拿 token |
| `GET /api/meta` | 公开 | 船舶/泊位/潮汐/人员/设备/故障/演示账号 |
| `GET/POST /api/voyages` | 登录 / AGENT+ | 船次列表/提交 |
| `GET /api/voyages/{id}` | 登录 | 船次 + 全部方案详情 + 记录 + 事件 |
| `PATCH /api/voyages/{id}/eta` | AGENT+ | 补报 ETA；若有确认方案同时做 ETA 迟到影响分析 |
| `POST /api/voyages/{id}/withdraw` | AGENT+ | 撤回授权并释放该船全部资源 |
| `POST /api/voyages/{id}/candidates/generate` | DISPATCHER+ | 枚举候选并落库 |
| `GET /api/plans`、`GET /api/plans/{id}` | 登录 | 方案/分段/原因/分配 |
| `POST /api/plans/{id}/stage` | DISPATCHER+ | body：`{"holdResources":true,"holdTtlSeconds":120}` |
| `POST /api/plans/{id}/confirm` | DISPATCHER+ | 事务内重校 + 原子锁定 FIRM |
| `POST /api/plans/{id}/cancel` | DISPATCHER+ | 取消暂存并释放 |
| `POST /api/segments/{id}/start|complete|log` | TEAM/DISPATCHER | 开工/完成/部分作业台账 |
| `GET /api/teams/board` | 登录 | 仅 CONFIRMED 方案的引航/拖轮/岸桥/泊位看板 |
| `PUT /api/simulation/tides/{slotId}` | DISPATCHER+ | 修订潮汐并分析 |
| `POST /api/simulation/crane-faults` | DISPATCHER+ | 插入设备故障窗口并分析 |
| `POST /api/plans/{id}/replan` | DISPATCHER+ | 扰动重排（默认自动确认） |
| `POST /api/plans/{id}/change-berth` | DISPATCHER+ | 改靠/改时指令 |
| `GET /api/disruptions[/{id}]` | 登录 | 扰动与影响项 |
| `POST /api/replays/orderings|disruptions|full` | DISPATCHER+ | 一键回放 |
| `/api/hatch/**`（样例对比/校验/案例/实测/压载回执/实测重排/自验） | 登录；写操作 TEAM/DISPATCHER，自验 DISPATCHER | **舱口顺序·纵倾工作台，详见 [第 11 节](#11-舱口顺序--纵向力矩--岸桥交叉工作台本次新增)** |
| `POST /api/admin/reset` | DISPATCHER+ | 清空并恢复固定样例（同时清空 hatch_* 表） |

---

## 10. 配置与运维说明

- `app.planning.hold-on-stage`、`app.planning.default-hold-ttl-seconds`、
  `app.planning.candidate-step-minutes`、`app.planning.max-wait-minutes` 见 `application.yml`，
  均可用同名环境变量覆盖（`HOLD_ON_STAGE`、`HOLD_TTL_SECONDS` …）。
- `HoldSweeper` 每 500ms 扫描 `STAGED` 且到期的方案，释放其 `TENTATIVE` 占用并写 `HOLD_EXPIRED` 事件。
- `ccdev/ccdev` 是任务容器的隔离开发账号，**不要直接作为生产配置**；Compose 使用独立的 `berth` 库与密码。
- 服务不依赖 Redis/港口实机/第三方账号；外部潮汐与设备状态全部来自库内模拟表和模拟录入接口。

---

## 11. 舱口顺序 / 纵向力矩 / 岸桥交叉工作台（本次新增）

原有装卸表只写“岸桥数量”，本特性补齐现场必须决定的**舱口顺序、纵向（纵倾）力矩、岸桥禁入/交叉**，
生成带时间片的作业序列，并把**实测完成量**与**压载回执**用于后续重排。后端在独立的
`com.port.berth.hatch` 包（纯规划器 `HatchPlanner` + 实测基准 `HatchActuals` + 持久化 `HatchService`），
REST 为 `HatchController`（Swagger Tag「舱口顺序与纵倾工作台」），前端是单页第 **⑥** 个标签。

### 11.1 模型与力矩口径

- 舱口 `Hatch{code, positionM(x), cargoT, order}`：`x=0` 为船中，向船首为正。
- 压载舱 `BallastTank{code, positionM, maxChangeTPerHour}`（正为加压、负为排压）。
- 岸桥 `Crane{code, rateTPerHour, forbiddenHatches}`，默认每桥 ≤ 10 t/h。
- `sameHatchAllowed=false`（不得同舱）；`forbiddenSimultaneousPairs` 给禁止同时作业的舱对，
  样例把相邻的 `H1+H2`、`H2+H3` 标为 `CRANE_CROSS_FORBIDDEN`（相邻舱同时卸货→岸桥交叉），
  因此只有两侧的 `H1+H3` 可双桥并行。
- 时间片默认 60 分钟。纵向力矩递推（单位 t·m）：

```
M′ = M − Σ(卸货量 q_h × 舱位 x_h) + Σ(压载增减 Δb_t × 压载舱位 x_t)
```

  卸走 `x` 处重量会使剩余船舶力矩反向变化。样例 `M0=0`、限值 `±100`。
  片内力矩随同步作业完成比例线性变化，`M(λ)=M片首 + λ·Δ` 是 λ 的线性函数，
  绝对值最大值在端点，故只需校验片首/片末即可保证整个时间片不越限。

### 11.2 样例的确定结论（均由单元测试与 `/api/hatch/verify` 锁定）

构型：`H1 x=−20 货20`、`H2 x=0 货10`、`H3 x=+20 货20`；两桥各 10 t/h；中舱压载 `x=0 ±10 t/h`。

| 对照项 | 按舱序优先 `HATCH_ORDER`（舱序 H2→H1→H3） | 按岸桥并行 `PARALLEL_SIDES`（外侧舱优先 H1,H3,H2） |
| --- | --- | --- |
| **首小时安排** | `QC1→H2` 卸中舱 **10t**；`QC2` **空等**（同舱被拒、与中舱相邻的 H1/H3 交叉被拒） | `QC1→H1`、`QC2→H3`，**各卸 10t**，无空等 |
| **首小时卸货 / 片末力矩** | 10t / **0 t·m**（中舱 x=0） | 20t / **0 t·m**（`−10·(−20) −10·(+20)` 的力矩增量 +200−200=0） |
| **总时长** | **3 个 1h 片 = 3 小时** | **3 片 = 3 小时** |
| **空等岸桥片** | 1 | 1（在最后一片中舱） |
| **生成中被拒候选步骤（确定性口径）** | **5**：第1片 1 次同舱 + 2 次交叉；第2/3片各 1 次同舱重试 | **3**：每片 1 次同舱重试 |

- **为什么总时长都是 3h**：总量 50t、两桥合计 20t/h ⇒ 时间片容量下界 `ceil(50/20)=3`；
  且中舱 H2 与 H1/H3 都相邻禁同时，中舱那 10t 必须用 1 个“另一桥无法并行”的小时，
  两侧 40t 正好双桥并行 2 小时，故两种合规排法都达到下界 3 片。差异集中在**首小时产出/岸桥利用与被拒步骤**。
- **纵倾约束真的会拒步骤**：单桥首小时独卸 H1 10t → 力矩 `0 −10·(−20)=+200 t·m > +100`，
  该步以 `TRIM_MOMENT_EXCEEDED` 被拒（独卸 H3 同理得 −200）。这说明“先卸一侧重货”不可行，
  必须两侧平衡并行或借助有力臂的压载。
- **固定“被拒步骤”探针 `constraintProbes`（共 8 条：7 拒 1 放）**：
  `SAME_HATCH`（`SAME_HATCH_FORBIDDEN`）、`FORBIDDEN_PAIR_1/2`（H1+H2、H2+H3 → `CRANE_CROSS_FORBIDDEN`）、
  两个 `TRIM_ONE_SIDE_*`（`TRIM_MOMENT_EXCEEDED`，|M|=200）、`CRANE_RATE`（`CRANE_RATE_EXCEEDED`）、
  `BALLAST_RATE`（`BALLAST_RATE_EXCEEDED`），以及唯一放行的正对照 `LEGAL_PARALLEL_SIDES`（H1+H3，力矩 0）。
  另可用 `POST /api/hatch/validate` 对任意候选时间片返回**全部**违例（如 H1 报 15t 会同时得到
  `CRANE_RATE_EXCEEDED` 与 `TRIM_MOMENT_EXCEEDED`，M=300）。

### 11.3 实测完成量、压载迟到回执与“计划≠实测”

落库三张表（见 `schema.sql`）：`hatch_case`（构型 JSON）、`hatch_plan`（每版计划/重排 JSON +
`provenance_json`）、`hatch_record`（**只增台账**：`CARGO_ACTUAL` 卸货实测 / `BALLAST_ORDER`
压载计划指令 / `BALLAST_RECEIPT` 压载实测回执）。`hatch_record` 与既有 `work_record` 一样由
PostgreSQL 触发器 `trg_hatch_record_immutable` 禁止 `UPDATE/DELETE`（错误码 `HATCH_RECORD_IMMUTABLE` → HTTP 409）。

重排起点状态由 `HatchActuals.build(...)` **只根据实测**计算：

- 各舱剩余 = 原货量 − **累计实测卸货量**（不是计划量）；
- 压载只累加**已收到回执**的实测增减；已下达但回执未到（迟到）的 `BALLAST_ORDER` 进入
  `pendingBallastReceipts`，其 `plannedChangeT` **不计入** `actualMomentTmM` 与重排构型，
  并在 `provenance.excludedPlannedBallast` 中逐条写明 `BALLAST_RECEIPT_PENDING`、
  `countedActualChangeT=0`、`usedPlannedAsActual=false`；
- 同时给出对照值 `momentIfPendingPlannedCountedTmM`：“若错误地把计划当实测”的力矩，仅用于警示。

自验/脚本固化的演示数值：实测 H1、H3 各卸 10t → 各剩 10t、实测力矩仍 0；下达中舱压载
**计划 +10t** 但**回执未到**就重排 ⇒ `pending=1`、`receivedBallast.BT-MID=0`、`actualMoment=0`、
`usedPlannedAsActual=false`；随后提交**迟到回执，实测 +8t（刻意≠计划 10t）** ⇒ 再重排时
`pending=0`、`receivedBallast.BT-MID=8`，重排只采用回执值 8。对有力臂的压载舱，
“是否把迟到计划计入”会真实改变力矩（单元测试用 x=10 的压载舱验证：实测 M=0，错误计入计划 +10 会得 +100）。

### 11.4 HTTP 接口（均需 `Authorization: Bearer <token>`）

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `GET /api/hatch`、`GET /api/hatch/sample`、`GET /api/hatch/compare` | 登录 | 样例构型 + 两策略计划 + 首小时/总时长/被拒对比 + 探针 |
| `GET /api/hatch/config` | 登录 | 仅样例 `HatchConfig` |
| `POST /api/hatch/compare` | 登录 | 对自定义 `HatchConfig` 做对比（body 可空=样例） |
| `POST /api/hatch/validate` | 登录 | 校验一个候选时间片，返回全部违例与试算力矩 |
| `GET /api/hatch/cases`、`GET /api/hatch/cases/{id}` | 登录 | 案例列表 / 详情（构型、实测状态、台账、各版计划、对比） |
| `POST /api/hatch/cases` | TEAM/DISPATCHER | 建持久化案例并生成 v1 计划（空 body=样例+并行） |
| `POST /api/hatch/cases/{id}/cargo-actuals` | TEAM/DISPATCHER | 登记本次实测卸货增量 `{hatchCode,deltaT,...}`，超货量 400 |
| `POST /api/hatch/cases/{id}/ballast-orders` | TEAM/DISPATCHER | 下压载计划 `{tankCode,plannedChangeT}`（此刻仅计划值） |
| `POST /api/hatch/cases/{id}/ballast-receipts` | TEAM/DISPATCHER | 压载实测回执 `{orderId|orderCode,actualChangeT,late}` |
| `POST /api/hatch/cases/{id}/replan` | TEAM/DISPATCHER | 只按实测重排，返回新计划与 `provenance` |
| `POST /api/hatch/verify` | DISPATCHER | 15 项确定性端到端自验（含真实 DB 触发器） |
| `POST /api/hatch/reset` | DISPATCHER | 清空 hatch_* 案例数据；全局 `POST /api/admin/reset` 也会一并 TRUNCATE |

命令行一键验证（登录 + 内置自验 + 独立 GET 断言 + 真实 HTTP 实测/回执/重排流）：

```bash
bash scripts/hatch-verify.sh                 # 默认 http://127.0.0.1:8080
# ... HATCH_HTTP_FLOW_PASSED / ALL_HATCH_CHECKS_PASSED
```

### 11.5 界面手工路径

打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录 → 标签 **⑥ 舱口顺序·纵倾工作台**：

1. 顶部即样例结论：舱位示意、两条力矩表、6 张 KPI（首小时卸货量、总时长、被拒数）。
2. 左右两张时间片表分别是“舱序优先 / 岸桥并行”，每行列出 QC→舱、空等岸桥、本片卸货与片末力矩；
   下方可展开各自的被拒候选步骤，最底是 8 条约束探针表。
3. 点 **用样例建立作业案例**：可“登记实测卸货量 / 下达压载计划 / 按最新实测重排”。
   压载计划在“提交回执”前显示 `待回执/迟到`，状态区红色提示其计划值未计入；
   提交回执（可把实测改成 8）后再重排，顶部 `provenance` 横幅显示采用的是回执实测值。
4. 点 **▶ 运行本题自验（15 项）**，页内列出每条断言；该操作会清空并重建 `HATCH-VERIFY` 案例。

### 11.6 本特性的测试

- `src/test/java/com/port/berth/hatch/HatchPlannerTest.java`：**9 个**纯 JUnit 用例，精确锁定
  11.2/11.3 的全部数值与“迟到压载计划不冒充实测”。`mvn test` 合计 **13** 个用例全绿，既有 4 个无回归。
- `scripts/hatch-verify.sh`：对运行中的服务做真实 HTTP 验证；开发期已在常规库 `ccdev:5432`
  与一个**全新空库**（临时 `hatchfresh`，8081 端口，验证后已删除）上分别跑通。
- 既有 `scripts/replay.sh` 在加入本特性后仍为 **46/46**，数据库重置 TRUNCATE 列表已包含三张 hatch 表。
