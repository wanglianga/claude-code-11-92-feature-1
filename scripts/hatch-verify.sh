#!/usr/bin/env bash
# 舱口顺序/纵倾/岸桥交叉 特性一键验证脚本
#   1) POST /api/hatch/verify ：15 项确定性自验（样例数值 + 迟到压载不冒充实测 + DB 不可改触发器）
#   2) GET  /api/hatch/sample ：独立核对首小时/总时长/被拒步骤
#   3) 真实 HTTP 走一遍：建案例 -> 实测卸货 -> 压载计划 -> 未回执先重排 -> 迟到回执(8≠计划10) -> 再重排
# 用法：bash scripts/hatch-verify.sh [BASE_URL]
# 依赖：curl、python3
set -u

BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
USERNAME="${DISPATCH_USER:-dispatch}"
PASSWORD="${DISPATCH_PASS:-dispatch123}"
PY="${PYTHON:-python3}"

echo "==> 目标服务: $BASE"

LOGIN_JSON=$(curl -sS --max-time 10 -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_JSON" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
if [ -z "$TOKEN" ]; then
  echo "登录失败：$LOGIN_JSON" >&2
  exit 2
fi
echo "==> 登录成功（$USERNAME）"

TMP=$(mktemp -d -t hatch-verify-XXXXXX)
trap 'rm -rf "$TMP"' EXIT
AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"

post() { # path data outfile
  curl -sS --max-time 60 -X POST "$BASE$1" -H "$AUTH" -H "$JSON" -d "$2" -o "$3" -w '%{http_code}'
}

# ---------- 1) 内置 15 项自验 ----------
HTTP=$(post /api/hatch/verify '{}' "$TMP/verify.json")
echo "==> POST /api/hatch/verify -> HTTP $HTTP"
if [ "$HTTP" != "200" ]; then echo "verify 请求失败：$(cat "$TMP/verify.json")" >&2; exit 1; fi

# ---------- 2) GET sample ----------
curl -sS --max-time 30 "$BASE/api/hatch/sample" -H "$AUTH" -o "$TMP/sample.json"

# ---------- 3) 真实 HTTP 状态流 ----------
HTTP=$(post /api/hatch/cases '{"code":"HATCH-SCRIPT-FLOW"}' "$TMP/case.json")
echo "==> POST /api/hatch/cases -> HTTP $HTTP"
[ "$HTTP" = "200" ] || { echo "建案例失败：$(cat "$TMP/case.json")" >&2; exit 1; }
CID=$("$PY" -c 'import json;print(json.load(open("'$TMP'/case.json"))["id"])')

post "/api/hatch/cases/$CID/cargo-actuals" '{"hatchCode":"H1","deltaT":10,"operatorName":"script"}' "$TMP/a1.json" >/dev/null
post "/api/hatch/cases/$CID/cargo-actuals" '{"hatchCode":"H3","deltaT":10,"operatorName":"script"}' "$TMP/a2.json" >/dev/null

HTTP=$(post "/api/hatch/cases/$CID/ballast-orders" '{"tankCode":"BT-MID","plannedChangeT":10}' "$TMP/order.json")
echo "==> 压载计划 -> HTTP $HTTP"
ORDER_ID=$("$PY" -c '
import json
d=json.load(open("'$TMP'/order.json"))
for r in d["records"]:
    if r["recType"]=="BALLAST_ORDER":
        print(r["id"]); break
')

HTTP=$(post "/api/hatch/cases/$CID/replan" '{}' "$TMP/replan_before.json")
echo "==> 未收回执先重排 -> HTTP $HTTP"

HTTP=$(post "/api/hatch/cases/$CID/ballast-receipts" \
  "{\"orderId\":$ORDER_ID,\"actualChangeT\":8,\"late\":true,\"operatorName\":\"script\"}" \
  "$TMP/receipt.json")
echo "==> 提交迟到回执(实测8 vs 计划10) -> HTTP $HTTP"

HTTP=$(post "/api/hatch/cases/$CID/replan" '{}' "$TMP/replan_after.json")
echo "==> 回执后再重排 -> HTTP $HTTP"

# ---------- 统一断言 ----------
"$PY" - "$TMP" <<'PY'
import json, os, sys
tmp = sys.argv[1]
fails = []
def load(n):
    with open(os.path.join(tmp, n), encoding='utf-8') as f:
        return json.load(f)
def chk(name, cond, extra=""):
    print(("  PASS " if cond else "  FAIL ") + name + ("" if cond else f"  {extra}"))
    if not cond:
        fails.append(name)

v = load("verify.json")
chk("内置自验 allPassed=true", v.get("allPassed") is True,
    f"{v.get('passed')}/{v.get('total')}")
for c in v.get("checks", []):
    if not c.get("pass"):
        chk("自验子项: " + c.get("name", "?"), False,
            f"expected={c.get('expected')} actual={c.get('actual')}")

s = load("sample.json")
pm = s["firstHour"]["parallelSides"]
ho = s["firstHour"]["hatchOrder"]
pm_h = sorted((m["hatchCode"], float(m["dischargeT"])) for m in pm["craneMoves"])
chk("并行首小时 = H1/H3 各10t", pm_h == [("H1", 10.0), ("H3", 10.0)], str(pm_h))
chk("并行首小时力矩=0", abs(float(pm["momentEndTmM"])) < 1e-9, pm["momentEndTmM"])
ho_h = [(m["hatchCode"], float(m["dischargeT"])) for m in ho["craneMoves"]]
chk("舱序首小时仅 H2 10t", ho_h == [("H2", 10.0)], str(ho_h))
chk("舱序首小时另一桥空等 QC2", ho.get("idleCranes") == ["QC2"], ho.get("idleCranes"))
td = s["totalDuration"]
chk("两策略总时长均为3", int(td["hatchOrderSlots"]) == 3 and int(td["parallelSlots"]) == 3,
    f'{td["hatchOrderSlots"]}/{td["parallelSlots"]}')
codes = {x for p in s["constraintProbes"] for x in [vv["code"] for vv in p["violations"]]}
chk("探针覆盖同舱/交叉/纵倾/速率",
    {"SAME_HATCH_FORBIDDEN", "CRANE_CROSS_FORBIDDEN",
     "TRIM_MOMENT_EXCEEDED", "CRANE_RATE_EXCEEDED"} <= codes, str(codes))

rb = load("replan_before.json")
p1 = rb["provenance"]
chk("回执未到 usedPlannedAsActual=false", p1["usedPlannedAsActual"] is False)
chk("重排前1条待回执", len(p1["pendingBallastReceipts"]) == 1)
chk("未回执 BT-MID 实测压载=0",
    abs(float(p1["receivedBallastChangeT"].get("BT-MID", 0))) < 1e-9)

ra = load("replan_after.json")
p2 = ra["provenance"]
bt = float(p2["receivedBallastChangeT"]["BT-MID"])
chk("回执后无待回执", len(p2["pendingBallastReceipts"]) == 0)
chk("重排采用回执实测 8t（非计划10t）", abs(bt - 8.0) < 1e-9, str(bt))
chk("回执后仍声明 usedPlannedAsActual=false", p2["usedPlannedAsActual"] is False)

if fails:
    print(f"\nHATCH_HTTP_FLOW_FAILED: {len(fails)} 项")
    sys.exit(1)
print("\nHATCH_HTTP_FLOW_PASSED")
PY
RC=$?
if [ $RC -ne 0 ]; then exit 1; fi

echo ""
echo "ALL_HATCH_CHECKS_PASSED"
