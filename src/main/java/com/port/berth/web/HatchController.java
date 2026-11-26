package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.hatch.HatchConfig;
import com.port.berth.hatch.HatchPlanner;
import com.port.berth.hatch.HatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 舱口顺序 / 纵向力矩 / 岸桥禁入工作台。
 * 提供两策略对比、时间片序列、被拒步骤、实测卸货量、压载回执（迟到）与实测重排。
 */
@RestController
@RequestMapping("/api/hatch")
@Tag(name = "舱口顺序与纵倾工作台",
        description = "舱位/货重/压载能力/岸桥禁入组合建模，时间片作业序列，"
                + "舱序优先 vs 岸桥并行对比，实测台账与“只认真实回执”的重排")
public class HatchController {

    private final HatchService hatchService;
    private final HatchPlanner planner;

    public HatchController(HatchService hatchService, HatchPlanner planner) {
        this.hatchService = hatchService;
        this.planner = planner;
    }

    // ---------------- 样例 / 对比 / 校验（登录即可） ----------------

    @GetMapping
    @Operation(summary = "工作台总览：样例构型 + 两策略计划 + 首小时/总时长/被拒步骤对比 + 约束探针")
    public Map<String, Object> overview() {
        return hatchService.sampleOverview();
    }

    @GetMapping("/sample")
    @Operation(summary = "题目固定样例的完整对比结果（GET 便于直接 curl）")
    public Map<String, Object> sample() {
        return hatchService.sampleOverview();
    }

    @GetMapping("/config")
    @Operation(summary = "题目样例构型：三舱 -20/0/20、货 20/10/20、力矩 ±100、两桥各 10t/h")
    public HatchConfig config() {
        return hatchService.sampleConfig();
    }

    @GetMapping("/compare")
    @Operation(summary = "用样例构型做两策略对比（GET）")
    public Map<String, Object> compareSampleGet() {
        return hatchService.comparison(null);
    }

    @PostMapping("/compare")
    @Operation(summary = "对自定义构型做两策略对比；请求体可留空或传完整 HatchConfig")
    public Map<String, Object> compare(@RequestBody(required = false) HatchConfig config) {
        return hatchService.comparison(config);
    }

    @PostMapping("/validate")
    @Operation(summary = "校验一个候选时间片：同舱/交叉/速率/货量/压载速率/纵倾力矩，返回全部违例")
    public HatchPlanner.Eval validate(@RequestBody(required = false) ValidateRequest req) {
        ValidateRequest r = req == null ? new ValidateRequest(null, null, null, null, null) : req;
        return planner.evaluate(r.config(), r.momentStartTmM(), r.remainingT(),
                r.steps(), r.ballastSteps());
    }

    // ---------------- 持久化案例 ----------------

    @GetMapping("/cases")
    @Operation(summary = "舱口工作台案例列表")
    public List<Map<String, Object>> cases() {
        return hatchService.listCases();
    }

    @PostMapping("/cases")
    @Operation(summary = "创建持久化案例并生成 v1 计划（默认样例 + 并行策略）")
    public Map<String, Object> createCase(@RequestBody(required = false) CreateCaseRequest req,
                                          HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.TEAM);
        CreateCaseRequest r = req == null ? new CreateCaseRequest(null, null, null, null, null) : req;
        return hatchService.createCase(r.code(), r.name(), r.voyageId(), r.strategy(), r.config());
    }

    @GetMapping("/cases/{id}")
    @Operation(summary = "案例详情：构型、实测状态、台账、各版计划与对比")
    public Map<String, Object> caseDetail(@PathVariable long id) {
        return hatchService.caseDetail(id);
    }

    @PostMapping("/cases/{id}/cargo-actuals")
    @Operation(summary = "登记某舱本次实测完成（卸货）增量；只增台账，超货量返回 400")
    public Map<String, Object> cargoActual(@PathVariable long id,
                                          @RequestBody CargoActualRequest req,
                                          HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        return hatchService.recordCargoActual(id, req.hatchCode(), req.deltaT(),
                req.operatorName(), req.note());
    }

    @PostMapping("/cases/{id}/ballast-orders")
    @Operation(summary = "下达压载调整计划指令（此时只是计划值，不是实测）")
    public Map<String, Object> ballastOrder(@PathVariable long id,
                                            @RequestBody BallastOrderRequest req,
                                            HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        return hatchService.createBallastOrder(id, req.tankCode(), req.plannedChangeT(), req.note());
    }

    @PostMapping("/cases/{id}/ballast-receipts")
    @Operation(summary = "提交压载实测回执（可标记 late 迟到）；重排只认真实回执值")
    public Map<String, Object> ballastReceipt(@PathVariable long id,
                                              @RequestBody ReceiptRequest req,
                                              HttpServletRequest http) {
        WebAuth.requireRole(http, Role.TEAM, Role.DISPATCHER);
        return hatchService.submitBallastReceipt(id, req.orderId(), req.orderCode(),
                req.actualChangeT(), req.late(), req.operatorName(), req.note());
    }

    @PostMapping("/cases/{id}/replan")
    @Operation(summary = "基于实测卸货量与已回执压载重排剩余作业；待回执压载的计划值被显式排除")
    public Map<String, Object> replan(@PathVariable long id,
                                      @RequestBody(required = false) ReplanRequest req,
                                      HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.TEAM);
        return hatchService.replan(id, req == null ? null : req.strategy());
    }

    // ---------------- 自验 / 重置 ----------------

    @PostMapping("/verify")
    @Operation(summary = "确定性自验：样例数值 + 迟到压载回执不冒充实测 + 实测重排 + DB 不可改触发器")
    public Map<String, Object> verify(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        return hatchService.verify();
    }

    @PostMapping("/reset")
    @Operation(summary = "清空舱口工作台案例/台账/计划（不影响泊位主数据；全局 /api/admin/reset 也会清空）")
    public Map<String, Object> reset(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER);
        hatchService.resetAllHatch();
        return Map.of("reset", true, "message", "舱口工作台数据已清空");
    }

    // ---------------- 请求 DTO ----------------

    public record CreateCaseRequest(String code, String name, Long voyageId,
                                    String strategy, HatchConfig config) {}

    public record CargoActualRequest(String hatchCode, Double deltaT,
                                     String operatorName, String note) {}

    public record BallastOrderRequest(String tankCode, Double plannedChangeT, String note) {}

    public record ReceiptRequest(Long orderId, String orderCode, Double actualChangeT,
                                 Boolean late, String operatorName, String note) {}

    public record ReplanRequest(String strategy) {}

    public record ValidateRequest(HatchConfig config, Double momentStartTmM,
                                  Map<String, Double> remainingT,
                                  List<HatchPlanner.CraneAssign> steps,
                                  List<HatchPlanner.BallastAssign> ballastSteps) {}
}
