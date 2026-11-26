package com.port.berth.hatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 舱口工作台：样例构型、两策略对比、时间片计划、实测卸货量/压载回执台账、
 * 以及“只依据实测”的重排。计划 JSON 与台账落 PostgreSQL。
 */
@Service
public class HatchService {

    public static final String CARGO_ACTUAL = "CARGO_ACTUAL";
    public static final String BALLAST_ORDER = "BALLAST_ORDER";
    public static final String BALLAST_RECEIPT = "BALLAST_RECEIPT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HatchPlanner planner;

    public HatchService(JdbcTemplate jdbc, ObjectMapper json, HatchPlanner planner) {
        this.jdbc = jdbc;
        this.json = json;
        this.planner = planner;
    }

    // ==================================================================
    // 无状态样例 / 对比
    // ==================================================================

    public HatchConfig sampleConfig() { return HatchConfig.sample(); }

    public Map<String, Object> sampleOverview() { return comparison(null); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> comparison(HatchConfig raw) {
        HatchConfig cfg = HatchConfig.orSample(raw);
        HatchPlan ho = planner.plan(cfg, HatchPlanner.HATCH_ORDER);
        HatchPlan pa = planner.plan(cfg, HatchPlanner.PARALLEL_SIDES);
        List<ProbeStep> probes = planner.probes(cfg);

        HatchPlan.Slot hoFirst = ho.slots().isEmpty() ? null : ho.slots().get(0);
        HatchPlan.Slot paFirst = pa.slots().isEmpty() ? null : pa.slots().get(0);

        Map<String, Object> firstHour = new LinkedHashMap<>();
        firstHour.put("slotMinutes", cfg.slotMinutes());
        firstHour.put("hatchOrder", firstSlotBrief(hoFirst));
        firstHour.put("parallelSides", firstSlotBrief(paFirst));
        firstHour.put("statement",
                "舱序优先首小时只卸中舱 H2 10t（x=0，力矩 0），另一台岸桥因不得同舱/与相邻舱交叉而空等；"
                        + "岸桥并行首小时 H1、H3 各卸 10t，±20m 力矩相消，力矩仍为 0。");

        Map<String, Object> totalDuration = new LinkedHashMap<>();
        totalDuration.put("hatchOrderSlots", ho.slotCount());
        totalDuration.put("hatchOrderHours", ho.totalDurationHours());
        totalDuration.put("parallelSlots", pa.slotCount());
        totalDuration.put("parallelHours", pa.totalDurationHours());
        totalDuration.put("equalMakespan", ho.slotCount() == pa.slotCount());
        totalDuration.put("hatchOrderOptimal", ho.makespanOptimal());
        totalDuration.put("parallelOptimal", pa.makespanOptimal());
        totalDuration.put("totalCargoT", ho.totalCargoT());
        totalDuration.put("combinedCraneCapacityPerSlotT",
                cfg.cranes().stream().mapToDouble(c -> c.rateTPerHour() * cfg.slotHours()).sum());
        totalDuration.put("capacityLowerBoundSlots", ho.capacityLowerBoundSlots());
        totalDuration.put("note",
                "总量 50t、两桥合计 20t/小时 ⇒ 时间片容量下界 ceil(50/20)=3；"
                        + "且中舱 H2 与 H1/H3 均为相邻禁入，中舱作业的 1 小时另一桥无法并行，"
                        + "两侧 40t 需并行 2 小时，故两种合规排法的总时长都为 3 小时；"
                        + "差异在首小时安排、岸桥空等与被拒步骤，而非总工期。");

        long probeRejected = probes.stream().filter(p -> !p.accepted()).count();
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("hatchOrderSearchRejectedSteps", ho.rejectedSteps().size());
        rejected.put("parallelSearchRejectedSteps", pa.rejectedSteps().size());
        rejected.put("hatchOrderCodeCounts", ho.rejectionCodeCounts());
        rejected.put("parallelCodeCounts", pa.rejectionCodeCounts());
        rejected.put("constraintProbeTotal", probes.size());
        rejected.put("constraintProbeRejected", probeRejected);
        rejected.put("constraintProbeAccepted", probes.size() - probeRejected);
        rejected.put("note",
                "searchRejectedSteps 是各策略在逐台填桥时尝试并被规则拒绝的候选动作；"
                        + "constraintProbes 是一组固定的违规试排（同舱/交叉/纵倾/速率）与合法并行正对照。");

        List<String> conclusions = List.of(
                "并行策略首小时：QC1→H1、QC2→H3，各卸 10t，片末纵向力矩 0 t·m。",
                "舱序优先首小时：仅 QC1→H2 卸中舱 10t，另一台岸桥空等，片末力矩 0 t·m。",
                "两种合规策略总时长均为 3 小时（3 个 1 小时时间片），都达到容量下界 3。",
                "单桥首小时独卸 H1 10t 会产生 +200 t·m 力矩，超过 ±100 限值，步骤被 TRIM_MOMENT_EXCEEDED 拒绝。",
                "同舱双桥、相邻舱 H1+H2 / H2+H3 同时作业分别被 SAME_HATCH_FORBIDDEN、CRANE_CROSS_FORBIDDEN 拒绝。",
                "压载回执未到时，计划压载量不计入实测状态与重排初始力矩（usedPlannedAsActual=false）。");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("feature", "HATCH_SEQUENCE_TRIM_CRANE_INTERFERENCE");
        root.put("config", cfg);
        root.put("hatchOrderPlan", ho);
        root.put("parallelPlan", pa);
        root.put("firstHour", firstHour);
        root.put("totalDuration", totalDuration);
        root.put("rejectedSteps", rejected);
        root.put("constraintProbes", probes);
        root.put("conclusions", conclusions);
        return (Map<String, Object>) view(root);
    }

    private Map<String, Object> firstSlotBrief(HatchPlan.Slot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) { m.put("empty", true); return m; }
        m.put("slot", s.slot());
        m.put("timeLabel", s.timeLabel());
        m.put("dischargedT", s.dischargedT());
        m.put("momentStartTmM", s.momentStartTmM());
        m.put("momentEndTmM", s.momentEndTmM());
        m.put("craneMoves", s.craneMoves());
        m.put("idleCranes", s.idleCranes());
        return m;
    }

    // ==================================================================
    // 持久化案例
    // ==================================================================

    public Map<String, Object> createCase(String code, String name, Long voyageId,
                                          String strategy, HatchConfig rawConfig) {
        HatchConfig cfg = HatchConfig.orSample(rawConfig);
        String strat = HatchPlanner.HATCH_ORDER.equalsIgnoreCase(strategy)
                ? HatchPlanner.HATCH_ORDER : HatchPlanner.PARALLEL_SIDES;
        String caseCode = (code == null || code.isBlank())
                ? "HATCH-" + System.currentTimeMillis() : code.trim();

        Long exists = jdbc.queryForObject("SELECT count(*) FROM hatch_case WHERE code=?",
                Long.class, caseCode);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("HATCH_CASE_CODE_EXISTS", "舱口工作台案例编号已存在: " + caseCode);
        }

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO hatch_case(code,name,voyage_id,strategy,config_json) "
                            + "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, caseCode);
            ps.setString(2, name == null ? "舱口顺序与纵倾工作台" : name);
            if (voyageId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, voyageId);
            ps.setString(4, strat);
            ps.setString(5, writeJson(cfg));
            return ps;
        }, kh);
        long id;
        if (kh.getKeys() != null && kh.getKeys().get("id") instanceof Number n) {
            id = n.longValue();
        } else if (!kh.getKeyList().isEmpty()
                && kh.getKeyList().get(0).get("id") instanceof Number n2) {
            id = n2.longValue();
        } else {
            throw new IllegalStateException("无法获取新建 hatch_case 的主键");
        }

        HatchPlan initial = planner.plan(cfg, strat);
        insertPlan(id, 1, "INITIAL_PLAN", strat, initial, null);
        return caseDetail(id);
    }

    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id, c.code, c.name, c.strategy, c.created_at, c.updated_at, "
                        + "(SELECT max(version_no) FROM hatch_plan p WHERE p.case_id=c.id) AS latest_version, "
                        + "(SELECT count(*) FROM hatch_record r WHERE r.case_id=c.id) AS record_count "
                        + "FROM hatch_case c ORDER BY c.id DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(asMap(view(r)));
        return out;
    }

    public Map<String, Object> caseDetail(long id) {
        Map<String, Object> caseRow = requireCaseRow(id);
        Aggregate agg = loadAggregate(id);

        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT id,version_no,basis,strategy,summary_json,plan_json,provenance_json,created_at "
                        + "FROM hatch_plan WHERE case_id=? ORDER BY version_no", id)) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.get("id"));
            pm.put("versionNo", p.get("version_no"));
            pm.put("basis", p.get("basis"));
            pm.put("strategy", p.get("strategy"));
            pm.put("summary", p.get("summary_json"));
            pm.put("plan", readJson((String) p.get("plan_json"), Object.class));
            String prov = (String) p.get("provenance_json");
            pm.put("provenance", prov == null ? null : readJson(prov, Object.class));
            pm.put("createdAt", String.valueOf(p.get("created_at")));
            plans.add(asMap(view(pm)));
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("actualDischargedT", agg.discharged);
        state.put("receivedBallastChangeT", agg.receivedBallast);
        state.put("pendingBallastReceipts", agg.basis.pendingBallast());
        state.put("hasPendingBallastReceipt", agg.basis.hasPendingBallast());
        state.put("actualMomentTmM", agg.basis.actualMomentTmM());
        state.put("momentIfPendingPlannedCountedTmM", agg.basis.momentIfPendingPlannedCountedTmM());
        state.put("remainingCargoT", remainingCargo(agg.cfg, agg.discharged));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", caseRow.get("id"));
        root.put("code", caseRow.get("code"));
        root.put("name", caseRow.get("name"));
        root.put("voyageId", caseRow.get("voyage_id"));
        root.put("strategy", caseRow.get("strategy"));
        root.put("createdAt", String.valueOf(caseRow.get("created_at")));
        root.put("updatedAt", String.valueOf(caseRow.get("updated_at")));
        root.put("config", agg.cfg);
        root.put("state", state);
        root.put("records", agg.recordViews);
        root.put("plans", plans);
        root.put("comparison", comparison(agg.cfg));
        return asMap(view(root));
    }

    // ==================================================================
    // 实测卸货量
    // ==================================================================

    public Map<String, Object> recordCargoActual(long caseId, String hatchCode,
                                                 Double deltaT, String operator, String note) {
        Aggregate agg = loadAggregate(caseId);
        HatchConfig.Hatch h;
        try {
            h = agg.cfg.hatch(hatchCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNKNOWN_HATCH", e.getMessage());
        }
        if (deltaT == null || deltaT <= HatchMath.EPS) {
            throw ApiException.badRequest("INVALID_AMOUNT", "本次实测卸货增量 deltaT 必须为正数");
        }
        double cum = HatchMath.round6(agg.discharged.getOrDefault(h.code(), 0d) + deltaT);
        if (cum > h.cargoT() + 1e-6) {
            throw ApiException.badRequest("ACTUAL_EXCEEDS_CARGO", String.format(Locale.ROOT,
                    "舱口 %s 应卸 %.1f t，已实测 %.1f t，本次再报 %.1f t 将累计 %.1f t，超出货量",
                    h.code(), h.cargoT(), cum - deltaT, deltaT, cum))
                    .details(Map.of("hatch", h.code(), "cargoT", h.cargoT(),
                            "alreadyActualT", cum - deltaT, "attemptDeltaT", deltaT));
        }
        jdbc.update("INSERT INTO hatch_record(case_id,rec_type,hatch_code,delta_t,actual_t,"
                        + "operator_name,note) VALUES (?,?,?,?,?,?,?)",
                caseId, CARGO_ACTUAL, h.code(), deltaT, cum, operator, note);
        touch(caseId);
        return caseDetail(caseId);
    }

    // ==================================================================
    // 压载计划指令 + 实测回执（可迟到；计划值不得冒充实测）
    // ==================================================================

    public Map<String, Object> createBallastOrder(long caseId, String tankCode,
                                                  Double plannedChangeT, String note) {
        Aggregate agg = loadAggregate(caseId);
        HatchConfig.BallastTank t;
        try {
            t = agg.cfg.tank(tankCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNKNOWN_BALLAST_TANK", e.getMessage());
        }
        if (plannedChangeT == null || Math.abs(plannedChangeT) <= HatchMath.EPS) {
            throw ApiException.badRequest("INVALID_AMOUNT",
                    "压载计划增减 plannedChangeT 不能为 0（正=加压载，负=排压载）");
        }
        // 预先取序列值作为主键，从而在只增表上一次性写入唯一 order_code（无需 UPDATE）。
        Long rid = jdbc.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('hatch_record','id'))", Long.class);
        String orderCode = "BO-" + rid;
        jdbc.update("INSERT INTO hatch_record(id,case_id,rec_type,order_code,tank_code,"
                        + "planned_t,note) VALUES (?,?,?,?,?,?,?)",
                rid, caseId, BALLAST_ORDER, orderCode, t.code(), plannedChangeT, note);
        touch(caseId);
        return caseDetail(caseId);
    }

    public Map<String, Object> submitBallastReceipt(long caseId, Long orderId, String orderCode,
                                                    Double actualChangeT, Boolean late,
                                                    String operator, String note) {
        Map<String, Object> order = findBallastOrder(caseId, orderId, orderCode);
        String oc = (String) order.get("order_code");
        String tank = (String) order.get("tank_code");

        Long dup = jdbc.queryForObject(
                "SELECT count(*) FROM hatch_record WHERE case_id=? AND rec_type=? AND order_code=?",
                Long.class, caseId, BALLAST_RECEIPT, oc);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("BALLAST_RECEIPT_EXISTS",
                    "压载指令 " + oc + " 已有实测回执；台账不可改，如需更正请补登调整回执后重排");
        }
        if (actualChangeT == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", "回执实测增减 actualChangeT 不能为空");
        }
        jdbc.update("INSERT INTO hatch_record(case_id,rec_type,order_code,tank_code,actual_t,"
                        + "late,operator_name,note) VALUES (?,?,?,?,?,?,?,?)",
                caseId, BALLAST_RECEIPT, oc, tank, actualChangeT,
                Boolean.TRUE.equals(late), operator, note);
        touch(caseId);
        return caseDetail(caseId);
    }

    private Map<String, Object> findBallastOrder(long caseId, Long orderId, String orderCode) {
        List<Map<String, Object>> rows;
        if (orderId != null) {
            rows = jdbc.queryForList("SELECT * FROM hatch_record WHERE id=? AND case_id=? "
                    + "AND rec_type=?", orderId, caseId, BALLAST_ORDER);
        } else if (orderCode != null && !orderCode.isBlank()) {
            rows = jdbc.queryForList("SELECT * FROM hatch_record WHERE order_code=? AND case_id=? "
                    + "AND rec_type=?", orderCode, caseId, BALLAST_ORDER);
        } else {
            throw ApiException.badRequest("ORDER_REQUIRED",
                    "必须通过 orderId 或 orderCode 指定要回执的压载指令");
        }
        if (rows.isEmpty()) {
            throw ApiException.notFound("未找到该压载计划指令（BALLAST_ORDER）");
        }
        return rows.get(0);
    }

    // ==================================================================
    // 基于实测重排
    // ==================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> replan(long caseId, String strategy) {
        Map<String, Object> caseRow = requireCaseRow(caseId);
        Aggregate agg = loadAggregate(caseId);
        String strat = (strategy == null || strategy.isBlank())
                ? (String) caseRow.get("strategy") : strategy;

        HatchPlan plan = planner.plan(agg.basis.remainingConfig(), strat);
        int version = nextVersion(caseId);

        List<Map<String, Object>> excluded = new ArrayList<>();
        for (HatchActuals.PendingBallast p : agg.basis.pendingBallast()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("orderCode", p.orderCode());
            e.put("tankCode", p.tankCode());
            e.put("plannedChangeT", p.plannedChangeT());
            e.put("countedActualChangeT", 0);
            e.put("reason", "BALLAST_RECEIPT_PENDING");
            e.put("message", "压载回执迟到/未到，计划值 " + p.plannedChangeT()
                    + "t 不计入实测状态，不能冒充实测");
            excluded.add(e);
        }

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("basis", "ACTUAL_MEASURED_ONLY");
        provenance.put("usedPlannedAsActual", false);
        provenance.put("actualDischargedT", agg.basis.actualDischargedT());
        provenance.put("receivedBallastChangeT", agg.basis.receivedBallastChangeT());
        provenance.put("pendingBallastReceipts", agg.basis.pendingBallast());
        provenance.put("excludedPlannedBallast", excluded);
        provenance.put("actualMomentTmM", agg.basis.actualMomentTmM());
        provenance.put("momentIfPendingPlannedCountedTmM",
                agg.basis.momentIfPendingPlannedCountedTmM());
        provenance.put("momentDifferenceTmM", HatchMath.round6(
                agg.basis.momentIfPendingPlannedCountedTmM() - agg.basis.actualMomentTmM()));
        provenance.put("remainingConfigInitialMomentTmM",
                agg.basis.remainingConfig().initialMomentTmM());
        provenance.put("warning", agg.basis.hasPendingBallast()
                ? "有 " + agg.basis.pendingBallast().size()
                        + " 条压载回执未到（迟到），其计划值已被排除在实测与重排之外"
                : null);

        insertPlan(caseId, version, "REPLANNED_FROM_ACTUALS", strat, plan, provenance);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("replan", true);
        root.put("caseId", caseId);
        root.put("versionNo", version);
        root.put("basis", "REPLANNED_FROM_ACTUALS");
        root.put("strategy", strat);
        root.put("plan", plan);
        root.put("provenance", provenance);
        root.put("caseDetail", caseDetail(caseId));
        return asMap(view(root));
    }

    // ==================================================================
    // 确定性自验（供 /api/hatch/verify 与 scripts/hatch-verify.sh）
    // ==================================================================

    public Map<String, Object> verify() {
        resetAllHatch();
        List<Map<String, Object>> checks = new ArrayList<>();

        HatchConfig cfg = HatchConfig.sample();
        HatchPlan pa = planner.plan(cfg, HatchPlanner.PARALLEL_SIDES);
        HatchPlan ho = planner.plan(cfg, HatchPlanner.HATCH_ORDER);

        check(checks, "并行策略首小时 H1/H3 各 10t",
                true,
                pa.slots().get(0).craneMoves().size() == 2
                        && pa.slots().get(0).craneMoves().stream()
                        .allMatch(m -> Math.abs(m.dischargeT() - 10) < 1e-9)
                        && pa.slots().get(0).craneMoves().stream()
                        .map(HatchPlan.CraneMove::hatchCode).sorted().toList()
                        .equals(List.of("H1", "H3")));
        check(checks, "并行首小时力矩为 0", 0d, pa.slots().get(0).momentEndTmM());
        check(checks, "舱序优先首小时仅中舱 H2 10t",
                "H2", ho.slots().get(0).craneMoves().size() == 1
                        ? ho.slots().get(0).craneMoves().get(0).hatchCode() : null);
        check(checks, "舱序优先首小时另一台岸桥空等", 1, ho.slots().get(0).idleCranes().size());
        check(checks, "两策略总时长均为 3 小时",
                Map.of("hatch", 3, "parallel", 3),
                Map.of("hatch", ho.slotCount(), "parallel", pa.slotCount()));

        // 端到端：建案例 -> 实测 -> 压载计划 -> 迟到未回执重排 -> 回执 -> 再重排
        Map<String, Object> created = createCase("HATCH-VERIFY", "自验案例", null,
                HatchPlanner.PARALLEL_SIDES, cfg);
        long caseId = ((Number) created.get("id")).longValue();
        check(checks, "创建持久化案例与 v1 计划", 1,
                ((List<?>) created.get("plans")).size());

        recordCargoActual(caseId, "H1", 10d, "verify", "首小时实测");
        recordCargoActual(caseId, "H3", 10d, "verify", "首小时实测");
        Map<String, Object> afterCargo = caseDetail(caseId);
        @SuppressWarnings("unchecked")
        Map<String, Object> st1 = (Map<String, Object>) afterCargo.get("state");
        @SuppressWarnings("unchecked")
        Map<String, Object> dischargedView = (Map<String, Object>) st1.get("actualDischargedT");
        checkBool(checks, "实测 H1/H3 各累计 10t",
                Math.abs(((Number) dischargedView.get("H1")).doubleValue() - 10d) < 1e-6
                        && Math.abs(((Number) dischargedView.get("H3")).doubleValue() - 10d) < 1e-6,
                Map.of("H1", 10, "H3", 10),
                Map.of("H1", dischargedView.get("H1"), "H3", dischargedView.get("H3")));

        Map<String, Object> withOrder = createBallastOrder(caseId, "BT-MID", 10d, "计划压载");
        Long orderRecordId = firstBallastOrderId(withOrder);

        Map<String, Object> beforeReceipt = replan(caseId, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> prov1 = (Map<String, Object>) beforeReceipt.get("provenance");
        check(checks, "压载回执未到时不把计划当实测 usedPlannedAsActual=false",
                false, prov1.get("usedPlannedAsActual"));
        check(checks, "重排前存在 1 条待回执压载", 1,
                ((List<?>) prov1.get("pendingBallastReceipts")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> ballast1 = (Map<String, Object>) prov1.get("receivedBallastChangeT");
        check(checks, "未回执压载不计入实测（BT-MID=0）", 0d,
                ((Number) ballast1.getOrDefault("BT-MID", 0)).doubleValue());
        check(checks, "未回执时实测力矩仍为 0", 0d,
                ((Number) prov1.get("actualMomentTmM")).doubleValue());

        submitBallastReceipt(caseId, orderRecordId, null, 8d, true, "verify", "迟到回执，实际 8t");
        Map<String, Object> afterReceipt = replan(caseId, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> prov2 = (Map<String, Object>) afterReceipt.get("provenance");
        check(checks, "回执到达后无待回执压载", 0,
                ((List<?>) prov2.get("pendingBallastReceipts")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> ballast2 = (Map<String, Object>) prov2.get("receivedBallastChangeT");
        double confirmed = ((Number) ballast2.get("BT-MID")).doubleValue();
        check(checks, "重排采用回执实测值 8t 而非计划 10t", 8d, confirmed);
        check(checks, "实测值不等于计划值（计划 10 未被冒充）", true,
                Math.abs(confirmed - 10d) > 1e-9);

        // 数据库触发器层自证不可改
        boolean triggerBlocks = false;
        String triggerMsg = null;
        try {
            jdbc.update("UPDATE hatch_record SET note='should-be-blocked' WHERE case_id=?", caseId);
        } catch (Exception ex) {
            triggerMsg = ex.getMessage();
            triggerBlocks = triggerMsg != null && triggerMsg.contains("HATCH_RECORD_IMMUTABLE");
        }
        check(checks, "DB 触发器阻止 UPDATE 实测台账", true, triggerBlocks);

        long passed = checks.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allPassed", passed == checks.size());
        result.put("passed", passed);
        result.put("total", checks.size());
        result.put("caseId", caseId);
        result.put("caseCode", "HATCH-VERIFY");
        result.put("checks", checks);
        return asMap(view(result));
    }

    private Long firstBallastOrderId(Map<String, Object> detail) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) detail.get("records");
        for (Map<String, Object> r : records) {
            if (BALLAST_ORDER.equals(r.get("recType"))) {
                return ((Number) r.get("id")).longValue();
            }
        }
        throw new IllegalStateException("未找到刚创建的压载指令");
    }

    public void resetAllHatch() {
        jdbc.execute("TRUNCATE TABLE hatch_record, hatch_plan, hatch_case "
                + "RESTART IDENTITY CASCADE");
    }

    // ==================================================================
    // 内部装配
    // ==================================================================

    private int nextVersion(long caseId) {
        Integer v = jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM hatch_plan "
                + "WHERE case_id=?", Integer.class, caseId);
        return v == null ? 1 : v;
    }

    private void insertPlan(long caseId, int version, String basis, String strategy,
                            HatchPlan plan, Map<String, Object> provenance) {
        jdbc.update("INSERT INTO hatch_plan(case_id,version_no,basis,strategy,summary_json,"
                        + "plan_json,provenance_json) VALUES (?,?,?,?,?,?,?)",
                caseId, version, basis, strategy, plan.summary(), writeJson(plan),
                provenance == null ? null : writeJson(provenance));
    }

    private void touch(long caseId) {
        jdbc.update("UPDATE hatch_case SET updated_at=? WHERE id=?", Timestamp.valueOf(LocalDateTime.now()), caseId);
    }

    private Map<String, Object> requireCaseRow(long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM hatch_case WHERE id=?", id);
        } catch (Exception e) {
            throw ApiException.notFound("舱口工作台案例不存在: " + id);
        }
    }

    private Aggregate loadAggregate(long caseId) {
        Map<String, Object> row = requireCaseRow(caseId);
        HatchConfig cfg = readJson((String) row.get("config_json"), HatchConfig.class);

        Map<String, Double> discharged = new LinkedHashMap<>();
        cfg.hatches().forEach(h -> discharged.put(h.code(), 0d));
        Map<String, Double> ballast = new LinkedHashMap<>();
        cfg.ballastTanks().forEach(t -> ballast.put(t.code(), 0d));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM hatch_record WHERE case_id=? ORDER BY id", caseId);
        List<Map<String, Object>> recordViews = new ArrayList<>();
        List<Map<String, Object>> orderRows = new ArrayList<>();
        java.util.Set<String> receivedCodes = new java.util.HashSet<>();

        for (Map<String, Object> r : rows) {
            String type = (String) r.get("rec_type");
            if (CARGO_ACTUAL.equals(type)) {
                String hc = (String) r.get("hatch_code");
                double d = num(r.get("delta_t"));
                discharged.merge(hc, d, Double::sum);
            } else if (BALLAST_RECEIPT.equals(type)) {
                String tc = (String) r.get("tank_code");
                ballast.merge(tc, num(r.get("actual_t")), Double::sum);
                if (r.get("order_code") != null) receivedCodes.add((String) r.get("order_code"));
            } else if (BALLAST_ORDER.equals(type)) {
                orderRows.add(r);
            }
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("id", r.get("id"));
            rv.put("recType", type);
            rv.put("orderCode", r.get("order_code"));
            rv.put("hatchCode", r.get("hatch_code"));
            rv.put("tankCode", r.get("tank_code"));
            rv.put("deltaT", r.get("delta_t"));
            rv.put("actualT", r.get("actual_t"));
            rv.put("plannedT", r.get("planned_t"));
            rv.put("late", r.get("late"));
            rv.put("operatorName", r.get("operator_name"));
            rv.put("note", r.get("note"));
            rv.put("createdAt", String.valueOf(r.get("created_at")));
            recordViews.add(rv);
        }

        List<HatchActuals.PendingBallast> pending = new ArrayList<>();
        for (Map<String, Object> o : orderRows) {
            String oc = (String) o.get("order_code");
            if (oc != null && receivedCodes.contains(oc)) continue;
            pending.add(new HatchActuals.PendingBallast(oc, (String) o.get("tank_code"),
                    num(o.get("planned_t"))));
        }

        HatchActuals.Basis basis = HatchActuals.build(cfg, discharged, ballast, pending);
        return new Aggregate(cfg, discharged, ballast, basis, recordViews);
    }

    private Map<String, Double> remainingCargo(HatchConfig cfg, Map<String, Double> discharged) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (HatchConfig.Hatch h : cfg.hatches()) {
            m.put(h.code(), Math.max(0d, HatchMath.round6(h.cargoT()
                    - discharged.getOrDefault(h.code(), 0d))));
        }
        return m;
    }

    private record Aggregate(HatchConfig cfg, Map<String, Double> discharged,
                             Map<String, Double> receivedBallast,
                             HatchActuals.Basis basis,
                             List<Map<String, Object>> recordViews) {}

    private static double num(Object o) {
        return o == null ? 0d : ((Number) o).doubleValue();
    }

    private void check(List<Map<String, Object>> checks, String name, Object expected, Object actual) {
        boolean pass;
        if (expected instanceof Number en && actual instanceof Number an) {
            pass = Math.abs(en.doubleValue() - an.doubleValue()) < 1e-6;
        } else {
            pass = java.util.Objects.equals(expected, actual);
        }
        checkBool(checks, name, pass, expected, actual);
    }

    private void checkBool(List<Map<String, Object>> checks, String name, boolean pass,
                           Object expected, Object actual) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("pass", pass);
        c.put("expected", expected);
        c.put("actual", actual);
        checks.add(c);
    }

    // ---------- JSON / 数值清洗 ----------

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    private <T> T readJson(String s, Class<T> type) {
        try { return json.readValue(s, type); }
        catch (Exception e) { throw new IllegalStateException("JSON 反序列化失败: " + e.getMessage(), e); }
    }

    private Object view(Object o) {
        Object converted = json.convertValue(o, Object.class);
        return clean(converted);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }

    private Object clean(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                r.put(String.valueOf(e.getKey()), clean(e.getValue()));
            }
            return r;
        }
        if (o instanceof List<?> l) {
            List<Object> r = new ArrayList<>(l.size());
            for (Object x : l) r.add(clean(x));
            return r;
        }
        if (o instanceof Double d) {
            if (Double.isFinite(d) && Math.abs(d - Math.rint(d)) < 1e-7) return d.longValue();
            return Math.round(d * 10000d) / 10000d;
        }
        if (o instanceof Float f) {
            if (Math.abs(f - Math.rint(f)) < 1e-7) return f.longValue();
        }
        return o;
    }
}
