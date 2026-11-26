package com.port.berth.hatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 舱口顺序/纵倾/岸桥干涉规划的输入构型。
 *
 * <p>纵向坐标 {@code positionM}：船中为 0，向船首为正、船尾为负。
 * 纵向力矩单位 t·m。力矩递推：
 * <pre>
 *   M_new = M_old − Σ(卸货量 q_h × 舱位 x_h) + Σ(压载增减 Δb_t × 压载舱位 x_t)
 * </pre>
 * 卸走 x 处的重量会使剩余船舶力矩反向变化；在 x=0 的中舱作业不改变力矩。
 */
public record HatchConfig(
        String shipCode,
        String shipName,
        double initialMomentTmM,
        double momentLimitTmM,          // 对称限值 ±limit
        Double momentLowerTmM,          // 可空：非对称下限；为空时取 -limit
        Double momentUpperTmM,          // 可空：非对称上限；为空时取 +limit
        int slotMinutes,
        boolean sameHatchAllowed,       // 两台岸桥是否允许同舱同时作业（样例 false）
        List<Hatch> hatches,
        List<BallastTank> ballastTanks,
        List<Crane> cranes,
        List<ForbiddenPair> forbiddenSimultaneousPairs
) {

    /** 货舱：位置、待卸量、舱序优先级（order 越小越优先）。 */
    public record Hatch(String code, String name, double positionM,
                        double cargoT, int order) {}

    /** 压载舱：位置 + 每小时最多增减吨数（rateTPerHour）。 */
    public record BallastTank(String code, String name, double positionM,
                               double maxChangeTPerHour, Double capacityT) {}

    /** 岸桥：编号、每小时最大卸货吨数、显式禁入舱口集合（可空）。 */
    public record Crane(String code, String name, double rateTPerHour,
                        List<String> forbiddenHatches) {}

    /** 禁止同时作业的舱口组合（相邻舱→岸桥交叉等），可带原因码。 */
    public record ForbiddenPair(String hatchA, String hatchB, String code, String reason) {}

    public double momentLower() {
        return momentLowerTmM != null ? momentLowerTmM : -momentLimitTmM;
    }

    public double momentUpper() {
        return momentUpperTmM != null ? momentUpperTmM : momentLimitTmM;
    }

    public double slotHours() { return slotMinutes / 60d; }

    public Hatch hatch(String code) {
        return hatches.stream().filter(h -> h.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知舱口: " + code));
    }

    public BallastTank tank(String code) {
        return ballastTanks.stream().filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知压载舱: " + code));
    }

    public Crane crane(String code) {
        return cranes.stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知岸桥: " + code));
    }

    /** 题目给定的固定样例：三舱 -20/0/20，货重 20/10/20，力矩限值 ±100。 */
    public static HatchConfig sample() {
        List<Hatch> hatches = List.of(
                new Hatch("H1", "1号舱（船尾侧）", -20, 20, 20),
                new Hatch("H2", "2号舱（中舱）", 0, 10, 10),
                new Hatch("H3", "3号舱（船首侧）", 20, 20, 30));
        List<BallastTank> tanks = List.of(
                new BallastTank("BT-MID", "中舱压载舱", 0, 10, null));
        List<Crane> cranes = List.of(
                new Crane("QC1", "一号岸桥", 10, List.of()),
                new Crane("QC2", "二号岸桥", 10, List.of()));
        // 相邻舱同时作业 -> 岸桥交叉；H1+H3（两侧）不相邻，允许并行。
        List<ForbiddenPair> pairs = List.of(
                new ForbiddenPair("H1", "H2", "CRANE_CROSS_FORBIDDEN",
                        "相邻舱同时卸货会导致两台岸桥交叉，禁止同时间片作业"),
                new ForbiddenPair("H2", "H3", "CRANE_CROSS_FORBIDDEN",
                        "相邻舱同时卸货会导致两台岸桥交叉，禁止同时间片作业"));
        return new HatchConfig("SAMPLE-HATCH", "样例散货船·三舱",
                0, 100, -100d, 100d, 60, false,
                hatches, tanks, cranes, pairs);
    }

    /** 用请求里可能缺字段的构型合并默认值，避免 NPE。 */
    public static HatchConfig orSample(HatchConfig in) {
        if (in == null) return sample();
        HatchConfig d = sample();
        double limit = in.momentLimitTmM() > 0 ? in.momentLimitTmM() : d.momentLimitTmM();
        int slot = in.slotMinutes() > 0 ? in.slotMinutes() : 60;
        List<Hatch> hs = in.hatches() == null || in.hatches().isEmpty() ? d.hatches() : in.hatches();
        List<BallastTank> ts = in.ballastTanks() == null ? List.of() : in.ballastTanks();
        if (in.ballastTanks() == null) ts = d.ballastTanks();
        List<Crane> cs = in.cranes() == null || in.cranes().isEmpty() ? d.cranes() : in.cranes();
        List<ForbiddenPair> ps = in.forbiddenSimultaneousPairs() == null
                ? List.of() : in.forbiddenSimultaneousPairs();
        return new HatchConfig(
                in.shipCode() != null ? in.shipCode() : d.shipCode(),
                in.shipName() != null ? in.shipName() : d.shipName(),
                in.initialMomentTmM(),
                limit,
                in.momentLowerTmM(), in.momentUpperTmM(),
                slot, in.sameHatchAllowed(),
                new ArrayList<>(hs), new ArrayList<>(ts), new ArrayList<>(cs), new ArrayList<>(ps));
    }
}
