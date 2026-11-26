package com.port.berth.hatch;

import java.util.List;
import java.util.Map;

/**
 * 一种排舱策略生成的完整时间片作业序列，以及生成过程中被拒绝的候选步骤。
 */
public record HatchPlan(
        String strategy,
        String strategyLabel,
        boolean feasible,
        String infeasibleReason,
        int slotMinutes,
        int slotCount,
        double totalDurationHours,
        int capacityLowerBoundSlots,
        boolean makespanOptimal,
        double totalCargoT,
        double totalDischargedT,
        double initialMomentTmM,
        double finalMomentTmM,
        int busyCraneSlots,
        int idleCraneSlots,
        List<Slot> slots,
        List<RejectedStep> rejectedSteps,
        Map<String, Integer> rejectionCodeCounts,
        String summary
) {

    /** 单个时间片内一台岸桥对一个舱的卸货动作。 */
    public record CraneMove(String craneCode, String hatchCode, String hatchName,
                            double positionM, double dischargeT) {}

    /** 单个时间片内的压载调整动作（计划或实测回执均复用）。 */
    public record BallastMove(String tankCode, String tankName, double positionM,
                              double changeT, double momentDeltaTmM) {}

    /** 一个时间片（默认 1 小时）。力矩在片内随作业完成比例线性变化，极值在片首/片尾。 */
    public record Slot(int slot, String timeLabel,
                       double momentStartTmM, double momentEndTmM,
                       double dischargedT,
                       List<CraneMove> craneMoves,
                       List<BallastMove> ballastMoves,
                       List<String> idleCranes) {}

    public record Violation(String code, String message) {}

    /** 一个被拒绝的候选/提交步骤，携带全部违例与当时的力矩试算。 */
    public record RejectedStep(Integer slot, String kind,
                               String craneCode, String hatchCode,
                               Double attemptedDischargeT,
                               String tankCode, Double attemptedBallastT,
                               Double momentAfterTmM,
                               List<Violation> violations,
                               String explanation) {}
}
