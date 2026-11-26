package com.port.berth.hatch;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 精确锁定题目样例：
 * 三舱 -20/0/20，货 20/10/20；力矩初值 0、限值 ±100；中舱压载 ±10t/h（x=0）；
 * 两桥各 10t/h，不得同舱/交叉，仅 H1+H3 可并行。
 */
class HatchPlannerTest {

    private final HatchPlanner planner = new HatchPlanner();
    private final HatchConfig cfg = HatchConfig.sample();

    private HatchPlan parallel() { return planner.plan(cfg, HatchPlanner.PARALLEL_SIDES); }
    private HatchPlan hatchOrder() { return planner.plan(cfg, HatchPlanner.HATCH_ORDER); }

    @Test
    void sampleConfigMatchesStatement() {
        assertEquals(0d, cfg.initialMomentTmM(), 1e-9);
        assertEquals(100d, cfg.momentLimitTmM(), 1e-9);
        Map<String, Double> pos = new LinkedHashMap<>();
        Map<String, Double> cargo = new LinkedHashMap<>();
        cfg.hatches().forEach(h -> { pos.put(h.code(), h.positionM()); cargo.put(h.code(), h.cargoT()); });
        assertEquals(-20d, pos.get("H1"), 1e-9);
        assertEquals(0d, pos.get("H2"), 1e-9);
        assertEquals(20d, pos.get("H3"), 1e-9);
        assertEquals(Map.of("H1", 20d, "H2", 10d, "H3", 20d), cargo);
        assertEquals(10d, cfg.cranes().get(0).rateTPerHour(), 1e-9);
        assertEquals(10d, cfg.ballastTanks().get(0).maxChangeTPerHour(), 1e-9);
    }

    @Test
    void parallelFirstHourServesTwoSideHatchesTenEachWithZeroMoment() {
        HatchPlan p = parallel();
        assertTrue(p.feasible(), p::infeasibleReason);
        HatchPlan.Slot first = p.slots().get(0);
        assertEquals(1, first.slot());
        assertEquals(2, first.craneMoves().size(), "首小时两台岸桥都应作业");
        assertEquals(0, first.idleCranes().size(), "并行策略首小时不应有空等岸桥");

        Map<String, Double> byHatch = new LinkedHashMap<>();
        first.craneMoves().forEach(m -> byHatch.put(m.hatchCode(), m.dischargeT()));
        assertEquals(10d, byHatch.get("H1"), 1e-9);
        assertEquals(10d, byHatch.get("H3"), 1e-9);
        assertNull(byHatch.get("H2"), "首小时不应卸中舱");
        assertEquals(20d, first.dischargedT(), 1e-9);
        assertEquals(0d, first.momentStartTmM(), 1e-9);
        assertEquals(0d, first.momentEndTmM(), 1e-9, "两侧等量卸载力矩相消，仍为 0");
    }

    @Test
    void hatchOrderFirstHourTakesMiddleOnlyAndOtherCraneIdle() {
        HatchPlan h = hatchOrder();
        assertTrue(h.feasible(), h::infeasibleReason);
        HatchPlan.Slot first = h.slots().get(0);
        assertEquals(1, first.craneMoves().size(), "舱序优先首小时只有一台岸桥能上中舱");
        HatchPlan.CraneMove only = first.craneMoves().get(0);
        assertEquals("H2", only.hatchCode());
        assertEquals(10d, only.dischargeT(), 1e-9);
        assertEquals(1, first.idleCranes().size(), "另一台岸桥必须空等");
        assertEquals(0d, first.momentEndTmM(), 1e-9, "中舱 x=0，力矩不变");
    }

    @Test
    void bothStrategiesFinishInThreeOneHourSlotsAndAreCapacityOptimal() {
        HatchPlan p = parallel();
        HatchPlan h = hatchOrder();
        assertEquals(3, p.slotCount());
        assertEquals(3, h.slotCount());
        assertEquals(3d, p.totalDurationHours(), 1e-9);
        assertEquals(3d, h.totalDurationHours(), 1e-9);
        // 50t 总量 / 两台合计 20t 每片 = 2.5 -> 至少 3 片
        assertEquals(3, p.capacityLowerBoundSlots());
        assertTrue(p.makespanOptimal());
        assertTrue(h.makespanOptimal());
        // 每个策略恰好有一个“空等岸桥片”
        assertEquals(1, p.idleCraneSlots());
        assertEquals(1, h.idleCraneSlots());
    }

    @Test
    void deterministicRejectedSearchSteps() {
        // 舱序优先：首片 QC2 同舱 H2 被拒 1 次 + 与中舱相邻交叉 H1/H3 各被拒 1 次；
        // 后两片各有 1 次同舱重试 -> 合计 5。
        HatchPlan h = hatchOrder();
        assertEquals(5, h.rejectedSteps().size(),
                () -> "实际被拒数=" + h.rejectedSteps().size() + " -> " + h.rejectedSteps());
        long firstSlot = h.rejectedSteps().stream().filter(r -> r.slot() == 1).count();
        assertEquals(3, firstSlot);
        assertTrue(h.rejectionCodeCounts().getOrDefault("SAME_HATCH_FORBIDDEN", 0) >= 3);
        assertEquals(2, h.rejectionCodeCounts().getOrDefault("CRANE_CROSS_FORBIDDEN", 0));

        // 并行：每片第二台桥各有一次同舱重试 -> 3。
        HatchPlan p = parallel();
        assertEquals(3, p.rejectedSteps().size(),
                () -> "实际被拒数=" + p.rejectedSteps().size() + " -> " + p.rejectedSteps());
    }

    @Test
    void probesExposeSameHatchCrossTrimRateAndLegalParallel() {
        List<ProbeStep> probes = planner.probes(cfg);
        Map<String, ProbeStep> byKey = new LinkedHashMap<>();
        probes.forEach(pr -> byKey.put(pr.key(), pr));

        ProbeStep same = byKey.get("SAME_HATCH");
        assertNotNull(same);
        assertFalse(same.accepted());
        assertTrue(hasCode(same, "SAME_HATCH_FORBIDDEN"));
        assertTrue(same.asExpected());

        boolean hasCross = probes.stream().anyMatch(pr -> !pr.accepted()
                && hasCode(pr, "CRANE_CROSS_FORBIDDEN"));
        assertTrue(hasCross, "应至少有一个相邻舱交叉被拒探针");

        // 单卸 H1（x=-20）10t -> 力矩 +200 > 100，纵倾被拒
        ProbeStep trim = probes.stream()
                .filter(pr -> pr.key().startsWith("TRIM_ONE_SIDE_"))
                .findFirst().orElseThrow();
        assertFalse(trim.accepted());
        assertTrue(hasCode(trim, "TRIM_MOMENT_EXCEEDED"));
        assertNotNull(trim.momentAfterTmM());
        assertEquals(200d, Math.abs(trim.momentAfterTmM()), 1e-6);

        ProbeStep rate = byKey.get("CRANE_RATE");
        assertNotNull(rate);
        assertFalse(rate.accepted());
        assertTrue(hasCode(rate, "CRANE_RATE_EXCEEDED"));

        ProbeStep legal = byKey.get("LEGAL_PARALLEL_SIDES");
        assertNotNull(legal);
        assertTrue(legal.accepted(), () -> "合法并行应通过: " + legal.violations());
        assertTrue(legal.asExpected());
    }

    @Test
    void oneSideFullDischargeAloneIsTrimRejected() {
        // 直接用 evaluate 复现“先卸一侧重货”：QC1 独卸 H1 10t
        HatchPlanner.Eval e = planner.evaluate(cfg, 0d, null,
                List.of(new HatchPlanner.CraneAssign("QC1", "H1", 10d)), null);
        assertFalse(e.accepted());
        assertTrue(e.violations().stream().anyMatch(v -> v.code().equals("TRIM_MOMENT_EXCEEDED")));
        assertEquals(200d, e.momentAfterTmM(), 1e-6);
    }

    @Test
    void lateBallastPlannedValueMustNotBecomeActual() {
        // 实测：H1/H3 各卸 10；压载指令计划 +10 但回执未到（pending）。
        Map<String, Double> discharged = new LinkedHashMap<>();
        discharged.put("H1", 10d);
        discharged.put("H3", 10d);
        List<HatchActuals.PendingBallast> pending = List.of(
                new HatchActuals.PendingBallast("B-1", "BT-MID", 10d));

        HatchActuals.Basis before = HatchActuals.build(cfg, discharged, Map.of(), pending);

        // 实测力矩只由卸货决定，仍为 0；待回执压载不计入。
        assertEquals(0d, before.actualMomentTmM(), 1e-9);
        assertTrue(before.hasPendingBallast());
        assertEquals(0d, before.receivedBallastChangeT().get("BT-MID"), 1e-9);
        // x=0 的中舱压载即便被错误计入，反事实力矩也仍是 0，但计划值绝不在实测 map 中。
        assertEquals(0d, before.momentIfPendingPlannedCountedTmM(), 1e-9);
        // 剩余量基于实测：每舱各剩 10
        assertEquals(10d, before.remainingConfig().hatch("H1").cargoT(), 1e-9);
        assertEquals(10d, before.remainingConfig().hatch("H2").cargoT(), 1e-9);
        assertEquals(10d, before.remainingConfig().hatch("H3").cargoT(), 1e-9);

        // 回执迟到后到达，实测只认回执值（这里实际 +8，与计划 +10 不同）。
        HatchActuals.Basis after = HatchActuals.build(cfg, discharged,
                Map.of("BT-MID", 8d), List.of());
        assertFalse(after.hasPendingBallast());
        assertEquals(8d, after.receivedBallastChangeT().get("BT-MID"), 1e-9);
        assertNotEquals(10d, after.receivedBallastChangeT().get("BT-MID"), 1e-9,
                "不能用计划值 10 冒充实测；实测为回执值 8");
    }

    @Test
    void pendingBallastWithLeverArmChangesCounterfactualMomentOnly() {
        // 构造一个压载舱有力臂（x=10）的构型，证明“是否把迟到计划当实测”会改变力矩，
        // 而 actualMoment 始终排除 pending。
        HatchConfig withArm = new HatchConfig("C", "n", 0, 100, -100d, 100d, 60, false,
                cfg.hatches(),
                List.of(new HatchConfig.BallastTank("BT-SIDE", "侧压载", 10, 10, null)),
                cfg.cranes(), cfg.forbiddenSimultaneousPairs());
        HatchActuals.Basis b = HatchActuals.build(withArm, Map.of(), Map.of(),
                List.of(new HatchActuals.PendingBallast("B-9", "BT-SIDE", 10d)));
        assertEquals(0d, b.actualMomentTmM(), 1e-9, "无回执 -> 实测压载为 0 -> 力矩 0");
        assertEquals(100d, b.momentIfPendingPlannedCountedTmM(), 1e-9,
                "若错误地把计划 +10 当实测，力矩会变成 +100（仅作对照，不用于重排）");
        assertEquals(0d, b.remainingConfig().initialMomentTmM(), 1e-9,
                "重排构型的初始力矩必须用实测值 0，而不是计划值 100");
    }

    private boolean hasCode(ProbeStep p, String code) {
        return p.violations().stream().anyMatch(v -> v.code().equals(code));
    }
}
