package com.port.berth.hatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实测台账到“重排起点状态”的纯计算。
 *
 * <p>关键纪律（题目要求）：压载回执迟到/未到时，<b>计划压载值绝不能计入实测状态</b>。
 * 在途/待回执的计划压载只出现在 {@code momentIfPendingPlannedCountedTmM} 这个“若错误地
 * 把计划当实测”的对照值里，绝不进入 {@link Basis#actualMomentTmM()} 与剩余构型。
 */
public final class HatchActuals {

    private HatchActuals() {}

    /** 尚未收到实测回执的压载计划指令。 */
    public record PendingBallast(String orderCode, String tankCode, double plannedChangeT) {}

    /** 重排所依据的、只含实测的状态。 */
    public record Basis(
            Map<String, Double> actualDischargedT,
            Map<String, Double> receivedBallastChangeT,
            List<PendingBallast> pendingBallast,
            double actualMomentTmM,
            double momentIfPendingPlannedCountedTmM,
            HatchConfig remainingConfig
    ) {
        /** 是否存在“计划值可能被误当实测”的未回执压载。 */
        public boolean hasPendingBallast() { return pendingBallast != null && !pendingBallast.isEmpty(); }
    }

    /**
     * @param cfg                    原始构型
     * @param actualDischargedT      各舱累计<b>实测</b>卸货量
     * @param receivedBallastChangeT 各压载舱累计<b>已回执实测</b>增减量
     * @param pendingBallast         已下达但回执未到的压载计划（不计入实测）
     */
    public static Basis build(HatchConfig cfg,
                              Map<String, Double> actualDischargedT,
                              Map<String, Double> receivedBallastChangeT,
                              List<PendingBallast> pendingBallast) {
        Map<String, Double> discharged = new LinkedHashMap<>();
        for (HatchConfig.Hatch h : cfg.hatches()) {
            discharged.put(h.code(), clamp0(actualDischargedT == null ? null
                    : actualDischargedT.get(h.code())));
        }
        Map<String, Double> ballast = new LinkedHashMap<>();
        for (HatchConfig.BallastTank t : cfg.ballastTanks()) {
            ballast.put(t.code(), clamp0(receivedBallastChangeT == null ? null
                    : receivedBallastChangeT.get(t.code())));
        }
        List<PendingBallast> pending = pendingBallast == null ? List.of() : List.copyOf(pendingBallast);

        double actualMoment = moment(cfg, cfg.initialMomentTmM(), discharged, ballast);

        // 反事实：若把未回执的计划压载也“当作”实测，力矩会是多少（仅用于警示/对照）。
        Map<String, Double> wronglyCounted = new LinkedHashMap<>(ballast);
        for (PendingBallast p : pending) {
            wronglyCounted.merge(p.tankCode(), p.plannedChangeT(), Double::sum);
        }
        double counterfactualMoment = moment(cfg, cfg.initialMomentTmM(), discharged, wronglyCounted);

        // 剩余货量 = 原货量 − 实测；重排初始力矩 = 实测当前力矩。
        List<HatchConfig.Hatch> remaining = cfg.hatches().stream()
                .map(h -> new HatchConfig.Hatch(h.code(), h.name(), h.positionM(),
                        Math.max(0d, HatchMath.round6(h.cargoT() - discharged.get(h.code()))),
                        h.order()))
                .toList();
        HatchConfig remainingConfig = new HatchConfig(
                cfg.shipCode(), cfg.shipName(),
                HatchMath.round6(actualMoment),
                cfg.momentLimitTmM(), cfg.momentLowerTmM(), cfg.momentUpperTmM(),
                cfg.slotMinutes(), cfg.sameHatchAllowed(),
                remaining, cfg.ballastTanks(), cfg.cranes(),
                cfg.forbiddenSimultaneousPairs());

        return new Basis(discharged, ballast, pending,
                HatchMath.round6(actualMoment),
                HatchMath.round6(counterfactualMoment),
                remainingConfig);
    }

    /** 按“卸重反向、压载同向”的递推计算当前纵向力矩。 */
    public static double moment(HatchConfig cfg, double initial,
                                Map<String, Double> discharged,
                                Map<String, Double> ballastChange) {
        double m = initial;
        for (HatchConfig.Hatch h : cfg.hatches()) {
            double q = discharged == null ? 0d : discharged.getOrDefault(h.code(), 0d);
            m -= q * h.positionM();
        }
        for (HatchConfig.BallastTank t : cfg.ballastTanks()) {
            double b = ballastChange == null ? 0d : ballastChange.getOrDefault(t.code(), 0d);
            m += b * t.positionM();
        }
        return HatchMath.round6(m);
    }

    private static double clamp0(Double v) { return v == null ? 0d : HatchMath.round6(v); }
}
