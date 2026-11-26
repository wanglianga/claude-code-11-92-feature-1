package com.port.berth.hatch;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.port.berth.hatch.HatchMath.EPS;

/**
 * 舱口顺序 + 纵向力矩 + 岸桥禁入的时间片规划器（纯逻辑，无 IO）。
 *
 * <p>两种确定性策略：
 * <ul>
 *   <li>{@code HATCH_ORDER}：严格按装卸表给定的舱序（样例中舱 H2 优先）逐台填岸桥，
 *       第二台岸桥不能同舱、又与相邻舱交叉时只能空等；</li>
 *   <li>{@code PARALLEL_SIDES}：外侧/两侧舱优先，使两台岸桥在首时间片即并行服务
 *       H1+H3，等量卸载让力矩始终为 0。</li>
 * </ul>
 */
@Component
public class HatchPlanner {

    public static final String HATCH_ORDER = "HATCH_ORDER";
    public static final String PARALLEL_SIDES = "PARALLEL_SIDES";

    /** 提交校验用的岸桥动作；dischargeT 为空表示按岸桥最大能力。 */
    public record CraneAssign(String craneCode, String hatchCode, Double dischargeT) {}
    /** 提交校验用的压载动作。 */
    public record BallastAssign(String tankCode, Double changeT) {}

    /** 一个时间片的联合校验结果。 */
    public record Eval(boolean accepted, double momentAfterTmM,
                       List<HatchPlan.Violation> violations,
                       List<HatchPlan.CraneMove> craneMoves,
                       List<HatchPlan.BallastMove> ballastMoves) {}

    // ==================================================================
    // 对外规划入口
    // ==================================================================

    public HatchPlan plan(HatchConfig raw, String strategy) {
        HatchConfig cfg = HatchConfig.orSample(raw);
        boolean hatchOrder = !PARALLEL_SIDES.equalsIgnoreCase(strategy);
        String strat = hatchOrder ? HATCH_ORDER : PARALLEL_SIDES;

        List<HatchConfig.Hatch> ordered = new ArrayList<>(cfg.hatches());
        if (hatchOrder) {
            ordered.sort(Comparator.comparingInt(HatchConfig.Hatch::order)
                    .thenComparing(h -> h.code()));
        } else {
            // 两侧/外侧舱优先：|x| 大者先，同 |x| 时靠船尾（x 小）在前 -> H1(-20),H3(20),H2(0)
            ordered.sort(Comparator.comparingDouble((HatchConfig.Hatch h) -> -Math.abs(h.positionM()))
                    .thenComparingDouble(HatchConfig.Hatch::positionM)
                    .thenComparing(HatchConfig.Hatch::code));
        }

        Map<String, Double> rem = new LinkedHashMap<>();
        double totalCargo = 0;
        for (HatchConfig.Hatch h : cfg.hatches()) {
            rem.put(h.code(), Math.max(0d, h.cargoT()));
            totalCargo += Math.max(0d, h.cargoT());
        }

        double moment = cfg.initialMomentTmM();
        List<HatchPlan.Slot> slots = new ArrayList<>();
        List<HatchPlan.RejectedStep> rejected = new ArrayList<>();
        int busy = 0, idle = 0;
        double totalDischarged = 0;
        String infeasible = null;

        double totalCraneCapacityPerSlot = cfg.cranes().stream()
                .mapToDouble(c -> c.rateTPerHour() * cfg.slotHours()).sum();
        int lbSlots = totalCraneCapacityPerSlot <= EPS ? Integer.MAX_VALUE
                : HatchMath.ceilSlots(totalCargo / totalCraneCapacityPerSlot);

        int maxSlots = Math.max(8, lbSlots + 6);
        int noProgressSlots = 0;

        for (int s = 1; s <= maxSlots; s++) {
            boolean anyLeft = rem.values().stream().anyMatch(v -> v > EPS);
            if (!anyLeft) break;

            Map<String, Double> allocated = new LinkedHashMap<>();
            List<HatchPlan.CraneMove> moves = new ArrayList<>();
            List<String> idleCranes = new ArrayList<>();

            for (HatchConfig.Crane crane : cfg.cranes()) {
                HatchConfig.Hatch choice = null;
                for (HatchConfig.Hatch h : ordered) {
                    double baseRemaining = rem.get(h.code());
                    if (baseRemaining <= EPS) continue;   // 片前已无货，直接跳过且不算被拒步骤
                    boolean occupied = allocated.containsKey(h.code());
                    double available = baseRemaining - allocated.getOrDefault(h.code(), 0d);

                    // 1) 不得同舱（即使本时间片已把该舱货量分满，也要留下同舱被拒记录）
                    if (!cfg.sameHatchAllowed() && occupied) {
                        double tryQ = Math.min(crane.rateTPerHour() * cfg.slotHours(),
                                baseRemaining);
                        rejected.add(candidate(s, "SEARCH", crane.code(), h.code(), tryQ, null,
                                List.of(v("SAME_HATCH_FORBIDDEN",
                                        "两台岸桥不得在同一时间片同卸舱口 " + h.code())),
                                "第二台岸桥尝试同舱作业，被“不得同舱”规则拒绝"));
                        continue;
                    }
                    if (available <= EPS) continue;
                    // 2) 岸桥显式禁入舱口
                    if (crane.forbiddenHatches() != null
                            && crane.forbiddenHatches().contains(h.code())) {
                        rejected.add(candidate(s, "SEARCH", crane.code(), h.code(), null, null,
                                List.of(v("CRANE_FORBIDDEN_HATCH",
                                        crane.code() + " 被列入舱口 " + h.code() + " 的禁入组合")),
                                "该岸桥对此舱口有显式禁入限制"));
                        continue;
                    }
                    // 3) 交叉/相邻禁入组合
                    HatchConfig.ForbiddenPair fp = pairConflict(cfg, h.code(), allocated.keySet());
                    if (fp != null) {
                        rejected.add(candidate(s, "SEARCH", crane.code(), h.code(), null, null,
                                List.of(v("CRANE_CROSS_FORBIDDEN", fp.reason()
                                        + "（" + fp.hatchA() + "↔" + fp.hatchB() + "）")),
                                "与同时间片已分配的 " + otherOf(fp, h.code())
                                        + " 舱构成交叉/禁入组合"));
                        continue;
                    }
                    choice = h;
                    break;
                }

                if (choice == null) {
                    idleCranes.add(crane.code());
                    idle++;
                    continue;
                }
                double available = rem.get(choice.code())
                        - allocated.getOrDefault(choice.code(), 0d);
                double q = HatchMath.round6(Math.min(
                        crane.rateTPerHour() * cfg.slotHours(), available));
                if (q <= EPS) {
                    idleCranes.add(crane.code());
                    idle++;
                    continue;
                }
                allocated.merge(choice.code(), q, Double::sum);
                moves.add(new HatchPlan.CraneMove(crane.code(), choice.code(), choice.name(),
                        choice.positionM(), q));
                busy++;
            }

            // 4) 纵向力矩联合校验；越限时按统一比例收缩本时间片卸货量
            double mEnd = momentOfCraneMoves(cfg, moment, moves);
            if (outOfMoment(cfg, mEnd)) {
                List<HatchPlan.Violation> vs = List.of(v("TRIM_MOMENT_EXCEEDED",
                        String.format(Locale.ROOT,
                                "按原能力排产片末纵向力矩 %.1f t·m，超出允许区间 [%.0f, %.0f]",
                                mEnd, cfg.momentLower(), cfg.momentUpper())));
                rejected.add(new HatchPlan.RejectedStep(s, "SEARCH_FULL_SLOT",
                        null, null, null, null, null,
                        HatchMath.round6(mEnd), vs,
                        "整个时间片按满能力排产会导致纵倾越限，该满排方案被拒绝并尝试收缩/平衡"));

                double lambda = momentSafeScale(cfg, moment, moves);
                if (lambda <= EPS + 1e-6) {
                    noProgressSlots++;
                    // 当前力矩下这些组合无法再安全卸货：记一次无法排产，留待平衡/压载或并行对侧
                    if (noProgressSlots >= 2) {
                        infeasible = "在纵向力矩 ±" + cfg.momentLimitTmM()
                                + " 与岸桥禁入组合约束下无法继续安全卸货；需要对侧平衡卸载或有效的压载调整";
                        break;
                    }
                    continue; // 再给一次组合选择机会（下一轮 ordered 不变，通常仍无解）
                }
                List<HatchPlan.CraneMove> scaled = new ArrayList<>();
                for (HatchPlan.CraneMove m : moves) {
                    double q = HatchMath.round6(m.dischargeT() * lambda);
                    if (q > EPS) {
                        scaled.add(new HatchPlan.CraneMove(m.craneCode(), m.hatchCode(),
                                m.hatchName(), m.positionM(), q));
                    }
                }
                moves = scaled;
                mEnd = momentOfCraneMoves(cfg, moment, moves);
            }

            double slotDischarged = moves.stream().mapToDouble(HatchPlan.CraneMove::dischargeT).sum();
            if (slotDischarged <= EPS) {
                noProgressSlots++;
                if (noProgressSlots >= 2) {
                    infeasible = "剩余舱口在当前约束下没有可安全执行的卸货动作（可能被纵倾/交叉约束锁死）";
                    break;
                }
                continue;
            }
            noProgressSlots = 0;

            for (HatchPlan.CraneMove m : moves) {
                rem.merge(m.hatchCode(), -m.dischargeT(), Double::sum);
            }
            totalDischarged += slotDischarged;
            slots.add(new HatchPlan.Slot(s, "第" + s + "小时",
                    HatchMath.round6(moment), HatchMath.round6(mEnd),
                    HatchMath.round6(slotDischarged),
                    List.copyOf(moves), List.of(), List.copyOf(idleCranes)));
            moment = mEnd;
        }

        boolean feasible = infeasible == null
                && rem.values().stream().noneMatch(x -> x > EPS);
        int slotCount = slots.size();
        double durationHours = slotCount * cfg.slotHours();
        boolean optimal = feasible && lbSlots != Integer.MAX_VALUE && slotCount == lbSlots;

        Map<String, Integer> codeCounts = new LinkedHashMap<>();
        for (HatchPlan.RejectedStep r : rejected) {
            r.violations().forEach(v -> codeCounts.merge(v.code(), 1, Integer::sum));
        }

        String label = hatchOrder ? "舱序优先（中舱先卸，另一台岸桥空等）"
                : "岸桥并行（两侧舱 H1+H3 同时，力矩相消）";
        String summary = feasible
                ? String.format(Locale.ROOT,
                        "%s：共 %d 个时间片（%.1f 小时）卸完 %.0f t，片末力矩 %.1f t·m；"
                                + "占用岸桥 %d 台片、空等 %d 台片；生成过程记录 %d 个被拒候选步骤。",
                        label, slotCount, durationHours, totalDischarged, moment,
                        busy, idle, rejected.size())
                : "该策略未能在约束内排完：" + infeasible;

        return new HatchPlan(strat, label, feasible, infeasible,
                cfg.slotMinutes(), slotCount, HatchMath.round6(durationHours),
                lbSlots == Integer.MAX_VALUE ? -1 : lbSlots, optimal,
                HatchMath.round6(totalCargo), HatchMath.round6(totalDischarged),
                HatchMath.round6(cfg.initialMomentTmM()), HatchMath.round6(moment),
                busy, idle, List.copyOf(slots), List.copyOf(rejected),
                codeCounts, summary);
    }

    // ==================================================================
    // 提交一组时间片动作的通用校验（/api/hatch/validate 与探针共用）
    // ==================================================================

    public Eval evaluate(HatchConfig cfg, Double momentStart,
                         Map<String, Double> remainingOverride,
                         List<CraneAssign> assigns,
                         List<BallastAssign> ballastAssigns) {
        cfg = HatchConfig.orSample(cfg);
        double m0 = momentStart != null ? momentStart : cfg.initialMomentTmM();
        List<HatchPlan.Violation> vs = new ArrayList<>();
        List<HatchPlan.CraneMove> moves = new ArrayList<>();
        List<HatchPlan.BallastMove> bmoves = new ArrayList<>();

        Map<String, Double> remaining = remainingOverride != null
                ? remainingOverride : defaultRemaining(cfg);

        for (CraneAssign a : assigns == null ? List.<CraneAssign>of() : assigns) {
            HatchConfig.Crane crane = findCrane(cfg, a.craneCode());
            HatchConfig.Hatch hatch = findHatch(cfg, a.hatchCode());
            if (crane == null) vs.add(v("UNKNOWN_CRANE", "未知岸桥: " + a.craneCode()));
            if (hatch == null) vs.add(v("UNKNOWN_HATCH", "未知舱口: " + a.hatchCode()));
            if (crane == null || hatch == null) continue;

            double cap = crane.rateTPerHour() * cfg.slotHours();
            double q = a.dischargeT() != null ? a.dischargeT() : cap;
            if (q < -EPS) {
                vs.add(v("INVALID_AMOUNT", "卸货量不能为负: " + q));
            }
            if (q > cap + 1e-6) {
                vs.add(v("CRANE_RATE_EXCEEDED", String.format(Locale.ROOT,
                        "%s 每时间片最多卸 %.1f t（速率 %.0f t/h × %.2f h），申报 %.1f t 超限",
                        crane.code(), cap, crane.rateTPerHour(), cfg.slotHours(), q)));
            }
            if (remaining != null && q > remaining.getOrDefault(hatch.code(), 0d) + 1e-6) {
                vs.add(v("CARGO_EXCEEDS_REMAINING", String.format(Locale.ROOT,
                        "舱口 %s 仅剩 %.1f t，不能再卸 %.1f t",
                        hatch.code(), remaining.getOrDefault(hatch.code(), 0d), q)));
            }
            if (crane.forbiddenHatches() != null
                    && crane.forbiddenHatches().contains(hatch.code())) {
                vs.add(v("CRANE_FORBIDDEN_HATCH",
                        crane.code() + " 禁入舱口 " + hatch.code()));
            }
            moves.add(new HatchPlan.CraneMove(crane.code(), hatch.code(), hatch.name(),
                    hatch.positionM(), q));
        }

        // 成对检查：同舱 + 禁入组合（交叉）
        for (int i = 0; i < moves.size(); i++) {
            for (int j = i + 1; j < moves.size(); j++) {
                HatchPlan.CraneMove a = moves.get(i), b = moves.get(j);
                if (a.hatchCode().equals(b.hatchCode())) {
                    if (!cfg.sameHatchAllowed()) {
                        vs.add(v("SAME_HATCH_FORBIDDEN",
                                a.craneCode() + " 与 " + b.craneCode() + " 被安排同卸 "
                                        + a.hatchCode() + "，违反“不得同舱”"));
                    }
                } else {
                    HatchConfig.ForbiddenPair fp = pairConflict(cfg, a.hatchCode(),
                            java.util.Set.of(b.hatchCode()));
                    if (fp != null) {
                        vs.add(v("CRANE_CROSS_FORBIDDEN",
                                a.craneCode() + " 在 " + a.hatchCode() + "、"
                                        + b.craneCode() + " 在 " + b.hatchCode()
                                        + " 构成禁止同时作业/交叉组合"));
                    }
                }
            }
        }

        for (BallastAssign ba : ballastAssigns == null ? List.<BallastAssign>of() : ballastAssigns) {
            HatchConfig.BallastTank t = findTank(cfg, ba.tankCode());
            double ch = ba.changeT() != null ? ba.changeT() : 0d;
            if (t == null) {
                vs.add(v("UNKNOWN_BALLAST_TANK", "未知压载舱: " + ba.tankCode()));
                continue;
            }
            double cap = t.maxChangeTPerHour() * cfg.slotHours();
            if (Math.abs(ch) > cap + 1e-6) {
                vs.add(v("BALLAST_RATE_EXCEEDED", String.format(Locale.ROOT,
                        "%s 每时间片最多增减 %.1f t，申报 %.1f t 超限", t.code(), cap, ch)));
            }
            bmoves.add(new HatchPlan.BallastMove(t.code(), t.name(), t.positionM(), ch,
                    HatchMath.round6(ch * t.positionM())));
        }

        double mAfter = momentOfMoves(cfg, m0, moves, bmoves);
        if (mAfter < cfg.momentLower() - 1e-6 || mAfter > cfg.momentUpper() + 1e-6) {
            vs.add(v("TRIM_MOMENT_EXCEEDED", String.format(Locale.ROOT,
                    "片末纵向力矩 %.1f t·m 超出允许区间 [%.0f, %.0f]",
                    mAfter, cfg.momentLower(), cfg.momentUpper())));
        }

        boolean accepted = vs.isEmpty();
        return new Eval(accepted, HatchMath.round6(mAfter), List.copyOf(vs),
                List.copyOf(moves), List.copyOf(bmoves));
    }

    // ==================================================================
    // 规范“被拒步骤”探针清单（同舱/交叉/纵倾/速率/压载速率 + 合法并行对照）
    // ==================================================================

    public List<ProbeStep> probes(HatchConfig raw) {
        HatchConfig cfg = HatchConfig.orSample(raw);
        List<ProbeStep> out = new ArrayList<>();
        Map<String, Double> full = defaultRemaining(cfg);
        List<HatchConfig.Crane> cs = cfg.cranes();
        if (cs.size() < 2 || cfg.hatches().isEmpty()) return out;
        String c1 = cs.get(0).code(), c2 = cs.get(1).code();

        // 1) 同舱
        HatchConfig.Hatch any = cfg.hatches().get(0);
        double qAny = Math.min(cs.get(0).rateTPerHour() * cfg.slotHours(), any.cargoT());
        Eval e1 = evaluate(cfg, cfg.initialMomentTmM(), full,
                List.of(new CraneAssign(c1, any.code(), qAny),
                        new CraneAssign(c2, any.code(), qAny)), null);
        out.add(probe("SAME_HATCH", "两台岸桥同卸 " + any.code(), false, e1,
                Map.of("hatch", any.code())));

        // 2) 每个禁入（相邻/交叉）舱对
        int pi = 0;
        for (HatchConfig.ForbiddenPair fp : cfg.forbiddenSimultaneousPairs()) {
            pi++;
            double qa = capFor(cfg, c1, fp.hatchA());
            double qb = capFor(cfg, c2, fp.hatchB());
            Eval e = evaluate(cfg, cfg.initialMomentTmM(), full,
                    List.of(new CraneAssign(c1, fp.hatchA(), qa),
                            new CraneAssign(c2, fp.hatchB(), qb)), null);
            out.add(probe("FORBIDDEN_PAIR_" + pi,
                    "禁入/交叉组合 " + fp.hatchA() + " + " + fp.hatchB() + " 同时作业",
                    false, e, Map.of("pair", List.of(fp.hatchA(), fp.hatchB()))));
        }

        // 3) 单侧重货：每个最外侧（|x| 最大）舱单独满速率，验证纵倾越限
        double maxAbsX = cfg.hatches().stream().mapToDouble(h -> Math.abs(h.positionM())).max().orElse(0);
        int sideIdx = 0;
        for (HatchConfig.Hatch h : cfg.hatches()) {
            if (Math.abs(Math.abs(h.positionM()) - maxAbsX) > 1e-6 || maxAbsX <= EPS) continue;
            sideIdx++;
            String crane = c1;
            double q = capFor(cfg, crane, h.code());
            Eval e = evaluate(cfg, cfg.initialMomentTmM(), full,
                    List.of(new CraneAssign(crane, h.code(), q)), null);
            out.add(probe("TRIM_ONE_SIDE_" + sideIdx,
                    "单台岸桥首小时独卸一侧 " + h.code() + " " + HatchMath.clean(q)
                            + "t（x=" + HatchMath.clean(h.positionM()) + "m）",
                    false, e, Map.of("hatch", h.code(), "positionM", h.positionM(),
                            "dischargeT", q, "limitTmM", cfg.momentLimitTmM())));
        }

        // 4) 岸桥速率超限：选最靠近船中（x 最小）的舱以隔离纵倾因素
        HatchConfig.Hatch mid = cfg.hatches().stream()
                .min(Comparator.comparingDouble(h -> Math.abs(h.positionM()))).orElse(any);
        double overQ = cs.get(0).rateTPerHour() * cfg.slotHours() * 1.5;
        Eval eRate = evaluate(cfg, cfg.initialMomentTmM(), full,
                List.of(new CraneAssign(c1, mid.code(), overQ)), null);
        out.add(probe("CRANE_RATE", "单台岸桥一个时间片申报 " + HatchMath.clean(overQ)
                + "t（超过速率上限，刻意选中舱隔离纵倾）", false, eRate,
                Map.of("hatch", mid.code(), "attemptedT", overQ)));

        // 5) 合法并行正对照：取分居两侧、互不为禁入对的两个最远舱
        HatchConfig.Hatch farL = cfg.hatches().stream()
                .min(Comparator.comparingDouble(HatchConfig.Hatch::positionM)).orElse(null);
        HatchConfig.Hatch farR = cfg.hatches().stream()
                .max(Comparator.comparingDouble(HatchConfig.Hatch::positionM)).orElse(null);
        if (farL != null && farR != null && !farL.code().equals(farR.code())
                && pairConflict(cfg, farL.code(), java.util.Set.of(farR.code())) == null) {
            double ql = capFor(cfg, c1, farL.code());
            double qr = capFor(cfg, c2, farR.code());
            Eval eOk = evaluate(cfg, cfg.initialMomentTmM(), full,
                    List.of(new CraneAssign(c1, farL.code(), ql),
                            new CraneAssign(c2, farR.code(), qr)), null);
            out.add(probe("LEGAL_PARALLEL_SIDES",
                    "合法并行：" + farL.code() + " " + HatchMath.clean(ql) + "t + "
                            + farR.code() + " " + HatchMath.clean(qr) + "t（力矩相消）",
                    true, eOk, Map.of("hatches", List.of(farL.code(), farR.code()))));
        }

        // 6) 压载速率超限（若配置了压载舱）
        if (!cfg.ballastTanks().isEmpty()) {
            HatchConfig.BallastTank t = cfg.ballastTanks().get(0);
            double over = t.maxChangeTPerHour() * cfg.slotHours() * 1.5;
            Eval eB = evaluate(cfg, cfg.initialMomentTmM(), full, null,
                    List.of(new BallastAssign(t.code(), over)));
            out.add(probe("BALLAST_RATE", "压载舱 " + t.code() + " 一个时间片申报增减 "
                    + HatchMath.clean(over) + "t（超过能力）", false, eB,
                    Map.of("tank", t.code(), "attemptedT", over)));
        }
        return out;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ProbeStep probe(String key, String title, boolean expectedAccepted, Eval e,
                            Map<String, Object> detail) {
        return new ProbeStep(key, title, expectedAccepted, e.accepted(),
                e.accepted() == expectedAccepted, e.momentAfterTmM(),
                e.violations(), detail);
    }

    private double capFor(HatchConfig cfg, String craneCode, String hatchCode) {
        HatchConfig.Crane c = findCrane(cfg, craneCode);
        HatchConfig.Hatch h = findHatch(cfg, hatchCode);
        double cap = c == null ? 10 : c.rateTPerHour() * cfg.slotHours();
        if (h != null) cap = Math.min(cap, Math.max(0, h.cargoT()));
        return HatchMath.round6(cap);
    }

    private Map<String, Double> defaultRemaining(HatchConfig cfg) {
        Map<String, Double> m = new LinkedHashMap<>();
        cfg.hatches().forEach(h -> m.put(h.code(), h.cargoT()));
        return m;
    }

    private HatchConfig.ForbiddenPair pairConflict(HatchConfig cfg, String hatchCode,
                                                   java.util.Set<String> otherHatches) {
        for (HatchConfig.ForbiddenPair p : cfg.forbiddenSimultaneousPairs()) {
            String other = null;
            if (p.hatchA().equals(hatchCode) && otherHatches.contains(p.hatchB())) other = p.hatchB();
            else if (p.hatchB().equals(hatchCode) && otherHatches.contains(p.hatchA())) other = p.hatchA();
            if (other != null) return p;
        }
        return null;
    }

    private String otherOf(HatchConfig.ForbiddenPair p, String hatchCode) {
        return p.hatchA().equals(hatchCode) ? p.hatchB() : p.hatchA();
    }

    private HatchConfig.Crane findCrane(HatchConfig cfg, String code) {
        return cfg.cranes().stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    private HatchConfig.Hatch findHatch(HatchConfig cfg, String code) {
        return cfg.hatches().stream().filter(h -> h.code().equals(code)).findFirst().orElse(null);
    }

    private HatchConfig.BallastTank findTank(HatchConfig cfg, String code) {
        return cfg.ballastTanks().stream().filter(t -> t.code().equals(code)).findFirst().orElse(null);
    }

    /** 仅岸桥卸货动作的力矩。 */
    private double momentOfCraneMoves(HatchConfig cfg, double m0,
                                      List<HatchPlan.CraneMove> moves) {
        double m = m0;
        for (HatchPlan.CraneMove mv : moves) m -= mv.dischargeT() * mv.positionM();
        return HatchMath.round6(m);
    }

    private double momentOfMoves(HatchConfig cfg, double m0,
                                 List<HatchPlan.CraneMove> moves,
                                 List<HatchPlan.BallastMove> bmoves) {
        double m = momentOfCraneMoves(cfg, m0, moves);
        for (HatchPlan.BallastMove bm : bmoves) m += bm.changeT() * bm.positionM();
        return HatchMath.round6(m);
    }

    private boolean outOfMoment(HatchConfig cfg, double m) {
        return m < cfg.momentLower() - 1e-6 || m > cfg.momentUpper() + 1e-6;
    }

    /**
     * 统一收缩比例 λ：片内力矩 M(λ)=m0+λ·Δ 为 λ 的线性函数，
     * 故只需让两个端点落在允许区间即可保证整个同步作业过程不越限。
     */
    private double momentSafeScale(HatchConfig cfg, double m0,
                                   List<HatchPlan.CraneMove> moves) {
        double delta = momentOfCraneMoves(cfg, 0d, moves); // 在 m=0 基准上算动作贡献
        double lambda = 1d;
        if (delta > EPS) {
            lambda = Math.min(lambda, (cfg.momentUpper() - m0) / delta);
        } else if (delta < -EPS) {
            lambda = Math.min(lambda, (cfg.momentLower() - m0) / delta);
        }
        if (lambda < 0) lambda = 0;
        return Math.min(1d, lambda);
    }

    private HatchPlan.RejectedStep candidate(int slot, String kind, String crane, String hatch,
                                             Double q, Double ballast,
                                             List<HatchPlan.Violation> vs, String explanation) {
        Double momentAfter = null; // 候选阶段尚未组成完整片，力矩由整槽校验补充
        return new HatchPlan.RejectedStep(slot, kind, crane, hatch, q, null, ballast,
                momentAfter, vs, explanation);
    }

    private static HatchPlan.Violation v(String code, String msg) {
        return new HatchPlan.Violation(code, msg);
    }
}
