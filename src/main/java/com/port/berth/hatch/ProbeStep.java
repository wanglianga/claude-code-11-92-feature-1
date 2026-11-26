package com.port.berth.hatch;

import java.util.List;
import java.util.Map;

/**
 * 一条“故意试排”的步骤及其校验结果，用于把同舱/交叉/纵倾/速率等硬约束的
 * 拒绝情形显式列成“被拒步骤”对照清单。
 */
public record ProbeStep(
        String key,
        String title,
        boolean expectedAccepted,
        boolean accepted,
        boolean asExpected,
        Double momentAfterTmM,
        List<HatchPlan.Violation> violations,
        Map<String, Object> detail
) {}
