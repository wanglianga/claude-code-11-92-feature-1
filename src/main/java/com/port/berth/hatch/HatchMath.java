package com.port.berth.hatch;

/**
 * 舱口/纵倾规划中的数值工具：统一浮点容差、精度与“整数则输出整数”的清洗。
 * 样例全部为整数吨位/米，双精度在该数量级下可精确表示，比较时统一走容差。
 */
public final class HatchMath {

    public static final double EPS = 1e-9;

    private HatchMath() {}

    public static boolean le0(double v) { return v <= EPS; }

    public static boolean eq(double a, double b) { return Math.abs(a - b) <= 1e-7; }

    /** 保留 6 位小数，消除 10.0000000002 之类的浮点尾差。 */
    public static double round6(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    /** 计算时间片容量下界需要的向上取整（50/20=2.5 -> 3），并扣除浮点误差。 */
    public static int ceilSlots(double ratio) {
        return (int) Math.ceil(ratio - 1e-9);
    }

    /**
     * JSON 展示清洗：接近整数的 double 转成 Long，使样例输出为 {@code 10}、{@code 0}
     * 而不是 {@code 10.0}/{@code 0.0}；否则返回保留 4 位的 double。
     */
    public static Number clean(double v) {
        double r = round6(v);
        if (Math.abs(r - Math.rint(r)) < 1e-7) return (long) Math.rint(r);
        return Math.round(v * 10_000d) / 10_000d;
    }
}
