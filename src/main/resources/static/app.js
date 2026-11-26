/* 港口靠泊协同服务前端（原生 JS，无构建） */
const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  token: localStorage.getItem('pb_token') || null,
  user: null,
  meta: null,
  voyages: [],
  agentSelected: null,
  plannerSelected: null,
  boardTimer: null,
};

const STATUS_LABEL = {
  SUBMITTED: '已提交', ETA_UPDATED: 'ETA 已补报', WITHDRAWN: '已撤回', CONFIRMED: '已有确认计划',
  CANDIDATE: '候选', STAGED: '已暂存·资源暂留中', EXPIRED: '暂留过期', CANCELLED: '已取消',
  SUPERSEDED: '已被新候选取代', REPLANNED: '已被重排替代',
};
const SEG_LABEL = {
  PILOTAGE: '引航', TUG_INBOUND: '拖轮靠泊', BERTHING: '靠泊操纵', CARGO_OP: '装卸作业',
  TUG_OUTBOUND: '拖轮离泊', UNBERTHING: '离泊',
};

function esc(o) {
  if (o === null || o === undefined) return '';
  return String(o).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDT(iso) {
  if (!iso) return '-';
  const s = String(iso);
  return s.replace('T', ' ').slice(5, 16);
}
function fmtFull(iso) { return iso ? String(iso).replace('T', ' ').slice(0, 19) : '-'; }
function toInputDT(iso) { return iso ? String(iso).slice(0, 16) : ''; }

function toast(msg, type = '') {
  const t = $('#toast');
  t.className = 'toast ' + type;
  t.innerHTML = msg;
  t.classList.remove('hidden');
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.add('hidden'), 5200);
}

async function api(path, options = {}) {
  const opts = { headers: { 'Content-Type': 'application/json' }, ...options };
  if (state.token) opts.headers.Authorization = 'Bearer ' + state.token;
  if (opts.body && typeof opts.body !== 'string') opts.body = JSON.stringify(opts.body);
  const res = await fetch(path, opts);
  if (res.status === 401) { logout(); throw new Error('未登录或登录已失效'); }
  let data = null;
  const text = await res.text();
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!res.ok) {
    const err = new Error((data && data.message) || ('HTTP ' + res.status));
    err.status = res.status; err.data = data;
    throw err;
  }
  return data;
}

function showError(err) {
  let extra = '';
  if (err.data && err.data.details && Array.isArray(err.data.details)) {
    extra = '<div style="margin-top:6px">' + err.data.details.slice(0, 8).map((r) =>
      `<div>· [${esc(r.code)}] ${esc(r.message)}</div>`).join('') + '</div>';
  }
  toast(`❌ ${esc(err.message)}${extra}`, 'error');
}

/* ---------------- 登录 ---------------- */
async function boot() {
  try {
    state.meta = await api('/api/meta');
    renderAccountChips();
    initMetaForms();
  } catch (e) { /* 未登录也允许渲染登录页账号提示？meta 需要 token，忽略 */ }

  if (state.token) {
    try {
      state.user = await api('/api/auth/me');
      enterApp();
      return;
    } catch { localStorage.removeItem('pb_token'); state.token = null; }
  }
  $('#login-view').classList.remove('hidden');
}

function renderAccountChips() {
  const accounts = (state.meta && state.meta.accounts) || [
    { username: 'agent', password: 'agent123', displayName: '船代' },
    { username: 'dispatch', password: 'dispatch123', displayName: '调度' },
  ];
  $('#account-chips').innerHTML = accounts.map((a) =>
    `<span class="chip" onclick="fillAccount('${a.username}','${a.password}')">${esc(a.username)} / ${esc(a.password)} · ${esc(a.displayName)}</span>`
  ).join('');
}
function fillAccount(u, p) { $('#login-username').value = u; $('#login-password').value = p; }

async function doLogin() {
  try {
    const r = await api('/api/auth/login', {
      method: 'POST',
      body: { username: $('#login-username').value.trim(), password: $('#login-password').value },
    });
    state.token = r.token; state.user = r.user;
    localStorage.setItem('pb_token', r.token);
    $('#login-view').classList.add('hidden');
    enterApp();
  } catch (e) { showError(e); }
}
function logout() {
  state.token = null; state.user = null;
  localStorage.removeItem('pb_token');
  location.reload();
}

async function enterApp() {
  $('#login-view').classList.add('hidden');
  $('#app-view').classList.remove('hidden');
  $('#current-user').textContent = `${state.user.displayName}（${state.user.role}）`;
  state.meta = await api('/api/meta');
  initMetaForms();
  await loadVoyages();
  loadTideTable();
  loadDisruptions();
  switchTab('agent');
}

function initMetaForms() {
  if (!state.meta) return;
  $('#f-ship').innerHTML = state.meta.ships.map((s) =>
    `<option value="${s.id}">${esc(s.code)} ${esc(s.name)}｜${s.lengthM}m/吃水${s.draftM}m/${esc(cargoLabel(s.cargoType))}</option>`).join('');
  $('#fault-crane').innerHTML = state.meta.cranes.map((c) =>
    `<option value="${c.id}" ${c.code === 'QC-2' ? 'selected' : ''}>${esc(c.code)} ${esc(c.name)}</option>`).join('');
  // 默认故障窗口：样例日 20:45-22:30
  if (!$('#fault-start').value) $('#fault-start').value = '2026-11-26T20:45';
  if (!$('#fault-end').value) $('#fault-end').value = '2026-11-26T22:30';
  if (!$('#f-eta-start').value) { $('#f-eta-start').value = '2026-11-26T18:00'; $('#f-eta-end').value = '2026-11-26T19:00'; }
}
function cargoLabel(c) {
  return { CONTAINER: '集装箱', BULK: '散货', TANKER: '液货', RORO: '滚装', GENERAL: '件杂' }[c] || c;
}

/* ---------------- Tabs ---------------- */
function switchTab(tab) {
  $$('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  $$('.tab-panel').forEach((p) => p.classList.remove('active'));
  $('#tab-' + tab).classList.add('active');
  if (tab === 'planner') { loadVoyages().then(renderPlannerSelect); }
  if (tab === 'team') { loadBoard(); startTeamTimer(); } else stopTeamTimer();
  if (tab === 'sim') { loadTideTable(); loadDisruptions(); }
  if (tab === 'hatch') { hatchOnShow(); }
}
function startTeamTimer() {
  stopTeamTimer();
  if ($('#team-auto').checked) state.boardTimer = setInterval(loadBoard, 5000);
}
function stopTeamTimer() { if (state.boardTimer) clearInterval(state.boardTimer); state.boardTimer = null; }
function toggleTeamTimer() { $('#team-auto').checked ? startTeamTimer() : stopTeamTimer(); }

/* ---------------- 船次 ---------------- */
async function loadVoyages() {
  state.voyages = await api('/api/voyages');
  renderVoyageList();
  renderPlannerSelect();
}

function renderVoyageList() {
  $('#voyage-list').innerHTML = state.voyages.map((v) => `
    <div class="voyage-item ${state.agentSelected === v.id ? 'selected' : ''}" onclick="selectAgentVoyage(${v.id})">
      <div>
        <div class="no">${esc(v.voyageNo)} <span class="badge ${v.status}">${STATUS_LABEL[v.status] || v.status}</span></div>
        <div class="meta">${esc(v.ship.name)} · ${v.ship.lengthM}m / 吃水 ${v.ship.draftM}m · ${cargoLabel(v.ship.cargoType)}</div>
        <div class="meta">ETA ${fmtDT(v.etaStart)} ~ ${fmtDT(v.etaEnd)} · 作业 ${v.workDurationMin}′ · 岸桥 ×${v.craneCount}</div>
      </div>
      <div class="meta">${esc(v.agentName || '')}</div>
    </div>`).join('');
}

function renderPlannerSelect() {
  const sel = $('#planner-voyage');
  if (!sel) return;
  const keep = state.plannerSelected || sel.value;
  sel.innerHTML = '<option value="">— 选择船次 —</option>' + state.voyages.map((v) =>
    `<option value="${v.id}">${esc(v.voyageNo)} · ${esc(v.ship.name)} · ETA ${fmtDT(v.etaStart)}</option>`).join('');
  if (keep) { sel.value = keep; state.plannerSelected = Number(keep) || keep; }
}

async function submitVoyage(ev) {
  ev.preventDefault();
  try {
    await api('/api/voyages', {
      method: 'POST',
      body: {
        shipId: Number($('#f-ship').value),
        voyageNo: $('#f-no').value || null,
        etaStart: $('#f-eta-start').value,
        etaEnd: $('#f-eta-end').value,
        workDurationMin: Number($('#f-duration').value),
        craneCount: Number($('#f-cranes').value),
        remark: $('#f-remark').value || null,
      },
    });
    toast('✅ 船次已提交', 'success');
    $('#f-no').value = '';
    await loadVoyages();
  } catch (e) { showError(e); }
  return false;
}

async function selectAgentVoyage(id) {
  state.agentSelected = id;
  renderVoyageList();
  const d = await api(`/api/voyages/${id}`);
  const v = d.voyage;
  $('#agent-detail').innerHTML = `
    <div class="card">
      <div class="row-between">
        <h2>${esc(v.voyageNo)} · ${esc(v.ship.name)}
          <span class="badge ${v.status}">${STATUS_LABEL[v.status] || v.status}</span></h2>
        <div class="row gap">
          <button class="btn" onclick="etaForm(${id})">补报 ETA</button>
          <button class="btn danger" onclick="withdraw(${id})">撤回授权</button>
        </div>
      </div>
      <div class="kv">
        船舶参数：${v.ship.lengthM}m / 吃水 ${v.ship.draftM}m / ${cargoLabel(v.ship.cargoType)}　|
        ETA <b>${fmtFull(v.etaStart)} ~ ${fmtFull(v.etaEnd)}</b>（修订 ${v.etaRevision} 次）　|
        作业 ${v.workDurationMin} 分钟，需岸桥 ${v.craneCount} 台<br/>
        船代：${esc(v.agentName)}　|　备注：${esc(v.remark || '-')}
      </div>
      <h3 class="section-title">不可变作业记录（引航 / 装卸台账）</h3>
      ${recordsTable(d.records)}
      <h3 class="section-title">事件流</h3>
      <div class="seg-list">${(d.events || []).map((ev) => `
        <div class="seg"><span>•</span><div><b>${esc(ev.event_type)}</b> ${esc(ev.message || '')}</div>
        <div class="when">${fmtFull(ev.created_at)}</div><div></div></div>`).join('') || '<span class="hint">暂无事件</span>'}
      </div>
    </div>`;
}

function recordsTable(records) {
  if (!records || !records.length) return '<p class="hint">暂无已完成记录。记录一旦写入，数据库触发器禁止修改/删除。</p>';
  return `<table class="data"><thead><tr><th>#</th><th>类型</th><th>班组/操作人</th><th>作业分钟</th><th>箱量</th><th>结果</th><th>完成时间</th></tr></thead><tbody>
    ${records.map((r) => `<tr><td>${r.id}</td><td>${r.record_type}</td><td>${esc(r.operator_name || '')}</td>
      <td>${r.work_minutes}</td><td>${r.moved_units}</td><td>${esc(r.result_note || '')}</td>
      <td class="mono">${fmtFull(r.completed_at)}</td></tr>`).join('')}
  </tbody></table>`;
}

async function etaForm(id) {
  const vals = await openForm({
    title: '补报 / 修订 ETA',
    fields: [
      { key: 'etaStart', label: '新 ETA 开始', type: 'datetime-local', required: true },
      { key: 'etaEnd', label: '新 ETA 结束', type: 'datetime-local', required: true },
      { key: 'note', label: '说明', type: 'text', value: '引航/潮位变化' },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/voyages/${id}/eta`, { method: 'PATCH', body: vals });
    if (r.etaImpact) toast('⚠️ ETA 迟到已冲击已确认计划，受影响工作段已标记，请前往调度工作台重排', 'error');
    else toast('✅ ETA 已补报', 'success');
    selectAgentVoyage(id); loadVoyages();
  } catch (e) { showError(e); }
}

async function withdraw(id) {
  if (!confirm('确认撤回该船次授权？相关暂存/确认方案将取消并释放资源，已完成记录仍保留。')) return;
  try { await api(`/api/voyages/${id}/withdraw`, { method: 'POST' }); toast('已撤回授权并释放资源'); selectAgentVoyage(id); loadVoyages(); }
  catch (e) { showError(e); }
}

/* ---------------- 调度工作台 ---------------- */
async function renderPlannerDetail() {
  const id = Number($('#planner-voyage').value);
  state.plannerSelected = id || null;
  if (!id) { $('#planner-detail').innerHTML = '<p class="hint">请选择船次并生成候选。</p>'; return; }
  const d = await api(`/api/voyages/${id}`);
  const plans = d.plans || [];
  $('#planner-detail').innerHTML = `
    <div class="kv" style="margin:8px 0">
      ${esc(d.voyage.voyageNo)} · ${esc(d.voyage.ship.name)} · 吃水 ${d.voyage.ship.draftM}m ·
      ETA ${fmtFull(d.voyage.etaStart)} ~ ${fmtFull(d.voyage.etaEnd)} · 状态
      <span class="badge ${d.voyage.status}">${STATUS_LABEL[d.voyage.status]}</span>
    </div>
    ${plans.length ? `<div class="cand-list">${plans.map(planCard).join('')}</div>`
      : '<p class="hint">尚未生成候选，点击右上角“生成/刷新候选计划”。</p>'}`;
}

function planCard(p) {
  const good = p.feasible;
  const isCandidate = ['CANDIDATE', 'EXPIRED', 'CANCELLED'].includes(p.status);
  return `
  <div class="cand ${good ? 'good' : 'bad'} ${p.status === 'CONFIRMED' ? 'recommended' : ''}">
    <div class="cand-head">
      <div class="cand-title">${esc(p.planNo)} · ${esc(p.berthCode || '')} ${esc(p.berthName || '')}</div>
      <div class="row gap">
        <span class="badge feasible-${p.feasible}">${p.feasible ? '可行' : '不可行/有等待'}</span>
        <span class="badge ${p.status}">${STATUS_LABEL[p.status] || p.status}</span>
      </div>
    </div>
    <div class="time-grid">
      <span>靠泊起点：<b>${fmtFull(p.berthFrom)}</b></span>
      <span>引航开始：<b>${fmtFull(p.pilotStart)}</b></span>
      <span>装卸窗口：<b>${fmtDT(p.opsStart)} ~ ${fmtDT(p.opsEnd)}</b></span>
      <span>离泊结束：<b>${fmtFull(p.berthTo)}</b></span>
      ${p.waitMinutes ? `<span>ETA 后等待：<b>${p.waitMinutes} 分钟</b></span>` : ''}
      ${p.holdExpiresAt ? `<span>暂留到期：<b style="color:#9a6b00">${fmtFull(p.holdExpiresAt)}</b></span>` : ''}
      ${p.releasedAt ? `<span>释放时间：<b>${fmtFull(p.releasedAt)}</b>（${esc(p.releaseReason || '')}）</span>` : ''}
      ${p.versionNo > 1 ? `<span>版本：<b>v${p.versionNo}</b> 替代 #${p.replanOfId}</span>` : ''}
    </div>
    <div class="assign">${(p.assignments || []).map((a) =>
      `<span class="res-chip">${({ BERTH: '泊位', PILOT: '引航', TUG: '拖轮', CRANE: '岸桥' })[a.resourceType] || a.resourceType} ${esc(a.code)} · ${esc(a.role || '')}</span>`).join('')}
    </div>
    ${(p.reasons || []).length ? `<div class="reasons">${p.reasons.map((r) =>
      `<div class="reason"><span class="code">[${esc(r.code)}]</span> ${esc(r.message)}
        ${r.holderVoyageNo ? `<br/>&nbsp;&nbsp;↳ 占用方 ${esc(r.holderVoyageNo)} / ${esc(r.holderPlanNo || '')}（${r.holderKind === 'TENTATIVE' ? '暂留' : '已确认'}${r.holdExpiresAt ? '，暂留至 ' + fmtFull(r.holdExpiresAt) : ''}）` : ''}
      </div>`).join('')}</div>` : ''}
    ${segmentTimeline(p.segments)}
    <div class="cand-actions">
      ${isCandidate ? `
        <label class="hint">TTL <input class="ttl-input" type="number" id="ttl-${p.id}" value="120" min="2" max="3600"/> 秒</label>
        <button class="btn primary tiny" onclick="stagePlan(${p.id}, true)">暂存并暂留资源</button>
        <button class="btn tiny" title="只保存候选不占资源，确认时原子锁定" onclick="stagePlan(${p.id}, false)">仅暂存不占资源</button>
        <button class="btn success tiny" onclick="confirmPlan(${p.id})">直接原子确认</button>` : ''}
      ${p.status === 'STAGED' ? `
        <button class="btn success tiny" onclick="confirmPlan(${p.id})">确认（转 FIRM 锁定）</button>
        <button class="btn danger tiny" onclick="cancelPlan(${p.id})">取消暂存并释放</button>` : ''}
      ${p.status === 'CONFIRMED' ? `
        <span class="badge CONFIRMED">✅ 已对引航/拖轮/岸桥班组生效</span>
        <button class="btn primary tiny" onclick="changeBerthPlan(${p.id},${p.berthId},'${toInputDT(p.berthFrom)}')">改靠/改时</button>
        <button class="btn warn tiny" onclick="replanPlan(${p.id})">扰动后重排（自动确认）</button>` : ''}
      <button class="btn tiny" onclick="toggleRaw(${p.id})">原始 JSON</button>
    </div>
    <pre class="json hidden" id="raw-${p.id}">${esc(JSON.stringify(p, null, 2))}</pre>
  </div>`;
}

function segmentTimeline(segs) {
  if (!segs || !segs.length) return '';
  return `<div class="seg-list">${segs.map((s) => `
    <div class="seg ${s.status} ${s.affected ? 'affected' : ''}">
      <span class="dot"></span>
      <div><b>${SEG_LABEL[s.segType] || s.segType}</b> ${esc(s.title)}
        <div class="res">${s.resourceCode ? '资源 ' + esc(s.resourceCode) : '（待分配）'}</div>
      </div>
      <div class="when">${fmtDT(s.startAt)}<br/>→ ${fmtDT(s.endAt)}</div>
      <span class="badge seg-${s.status}">${({ PLANNED: '计划', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消' })[s.status] || s.status}</span>
      ${s.affectReason ? `<div class="affect-note">⚠️ ${esc(s.affectReason)}</div>` : ''}
    </div>`).join('')}</div>`;
}

function toggleRaw(id) { $('#raw-' + id).classList.toggle('hidden'); }

async function generateCandidates() {
  const id = Number($('#planner-voyage').value);
  if (!id) return toast('请先选择船次', 'error');
  try {
    toast('⏳ 正在枚举泊位/潮位/人员/设备组合…');
    await api(`/api/voyages/${id}/candidates/generate`, { method: 'POST' });
    await renderPlannerDetail();
    toast('✅ 候选已生成', 'success');
  } catch (e) { showError(e); }
}

async function stagePlan(id, hold) {
  const ttlEl = $('#ttl-' + id);
  const ttl = ttlEl ? Number(ttlEl.value) : 120;
  try {
    await api(`/api/plans/${id}/stage`, { method: 'POST', body: { holdResources: hold, holdTtlSeconds: ttl } });
    toast(hold ? `✅ 已暂存并暂留全套资源，TTL ${ttl}s` : '✅ 已仅暂存候选（未占资源）', 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); await renderPlannerDetail(); }
}
async function confirmPlan(id) {
  try {
    await api(`/api/plans/${id}/confirm`, { method: 'POST' });
    toast('✅ 已确认：全套资源原子锁定，班组看板已生效', 'success');
    await renderPlannerDetail(); loadVoyages();
  } catch (e) { showError(e); await renderPlannerDetail(); }
}
async function cancelPlan(id) {
  if (!confirm('取消该暂存方案并释放资源？')) return;
  try { await api(`/api/plans/${id}/cancel`, { method: 'POST', body: { reason: '调度手动取消' } }); await renderPlannerDetail(); }
  catch (e) { showError(e); }
}
async function replanPlan(id) {
  if (!confirm('按当前最新潮汐/设备状态重排该计划？未完成段将被新版本替代，已完成记录保留。')) return;
  try {
    const r = await api(`/api/plans/${id}/replan`, { method: 'POST', body: { autoConfirm: true } });
    toast(`✅ 重排完成（${r.mode}），剩余 ${r.remainingMinutes} 分钟已排入新版本并确认`, 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); }
}
async function changeBerthPlan(id, currentBerthId, currentFrom) {
  const vals = await openForm({
    title: '下达改靠 / 改时指令',
    fields: [
      {
        key: 'newBerthId', label: '改靠泊位', type: 'select', value: String(currentBerthId),
        options: (state.meta.berths || []).map((b) => ({ value: String(b.id), label: `${b.code} ${b.name}` })),
      },
      { key: 'newBerthFrom', label: '新靠泊起点', type: 'datetime-local', value: currentFrom },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/plans/${id}/change-berth`, {
      method: 'POST',
      body: { newBerthId: Number(vals.newBerthId), newBerthFrom: vals.newBerthFrom, autoConfirm: true },
    });
    toast(`✅ 改靠已执行：${r.targetBerthCode} ${fmtDT(r.targetBerthFrom)}，受影响工作段已生成新版本`, 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); }
}

/* ---------------- 班组看板 ---------------- */
async function loadBoard() {
  let b;
  try { b = await api('/api/teams/board'); } catch { return; }
  const card = (c, kind) => `
    <div class="work-card ${c.affected ? 'affected' : ''}">
      <div class="wc-title">${esc(c.voyageNo)} · ${esc(c.shipName || '')}</div>
      <div class="wc-meta">
        ${esc(c.title || '')} ${c.resourceCode ? '· <b>' + esc(c.resourceCode) + '</b>' : ''}<br/>
        ${fmtDT(c.startAt || c.opsStart)} → ${fmtDT(c.endAt || c.opsEnd)}
        ${c.berthCode ? ' · 泊位 ' + esc(c.berthCode) : ''}
        ${c.craneCode ? ' · ' + esc(c.craneCode) : ''}
      </div>
      ${c.affected ? `<div class="reason" style="color:#b3261e;font-size:12px">⚠️ ${esc(c.affectReason || '受扰动影响')}</div>` : ''}
      ${c.segmentId ? segmentActions(c) : ''}
    </div>`;

  const segmentActions = (c) => {
    if (c.status === 'DONE') return `<span class="badge seg-DONE">已完成 · 记录 #${c.recordId || ''}</span>`;
    return `<div class="wc-actions">
      ${c.status !== 'IN_PROGRESS' ? `<button class="btn tiny" onclick="segStart(${c.segmentId})">开工</button>` : ''}
      ${c.segType === 'CARGO_OP' ? `<button class="btn tiny" onclick="segLog(${c.segmentId})">登记部分作业</button>` : ''}
      <button class="btn success tiny" onclick="segComplete(${c.segmentId},'${c.segType}')">完成并写记录</button>
    </div>`;
  };
  window.__segmentActions = segmentActions;

  $('#team-board').innerHTML = `
    <p class="hint">${esc(b.notice)}　当前生效确认方案：<b>${b.effectivePlans}</b></p>
    <div class="board-grid">
      <div class="team-col"><h3>🧑‍✈️ 引航 <span>${b.pilot.length}</span></h3>${b.pilot.map((c) => card(c, 'pilot')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🚢 拖轮 <span>${b.tug.length}</span></h3>${b.tug.map((c) => card(c, 'tug')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🏗️ 岸桥 <span>${b.crane.length}</span></h3>${b.crane.map((c) => card(c, 'crane')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🟫 泊位 <span>${b.berth.length}</span></h3>${b.berth.map((c) => card(c, 'berth')).join('') || '<p class="hint">暂无</p>'}</div>
    </div>`;
}

async function segStart(id) {
  try { await api(`/api/segments/${id}/start`, { method: 'POST', body: { operatorName: state.user.displayName } }); toast('▶️ 已开工'); loadBoard(); }
  catch (e) { showError(e); }
}
async function segLog(id) {
  const vals = await openForm({
    title: '登记部分装卸作业（写入不可变台账）',
    fields: [
      { key: 'operatorName', label: '班组/操作人', value: state.user.displayName },
      { key: 'workMinutes', label: '本次作业分钟', type: 'number', value: '30' },
      { key: 'movedUnits', label: '完成箱量', type: 'number', value: '36' },
      { key: 'resultNote', label: '备注', type: 'text', value: '作业正常' },
    ],
  });
  if (!vals) return;
  vals.workMinutes = Number(vals.workMinutes); vals.movedUnits = Number(vals.movedUnits);
  try { await api(`/api/segments/${id}/log`, { method: 'POST', body: vals }); toast('✅ 台账已登记（不可修改）', 'success'); loadBoard(); }
  catch (e) { showError(e); }
}
async function segComplete(id, segType) {
  const fields = [{ key: 'operatorName', label: '操作人', value: state.user.displayName }];
  if (segType === 'CARGO_OP') {
    fields.push({ key: 'workMinutes', label: '作业分钟', type: 'number', value: '60' });
    fields.push({ key: 'movedUnits', label: '完成箱量', type: 'number', value: '72' });
  }
  fields.push({ key: 'resultNote', label: '结果备注', type: 'text', value: '完成' });
  const vals = await openForm({ title: '完成工作段（写不可变记录）', fields });
  if (!vals) return;
  if (vals.workMinutes) vals.workMinutes = Number(vals.workMinutes);
  if (vals.movedUnits) vals.movedUnits = Number(vals.movedUnits);
  try { await api(`/api/segments/${id}/complete`, { method: 'POST', body: vals }); toast('✅ 已完成，记录固化', 'success'); loadBoard(); }
  catch (e) { showError(e); }
}

/* ---------------- 模态表单 ---------------- */
function openForm({ title, fields }) {
  return new Promise((resolve) => {
    const box = $('#modal-box');
    box.innerHTML = `<h3>${esc(title)}</h3>
      ${fields.map((f) => `
        <label style="display:block;margin:10px 0 4px;font-weight:600;font-size:13px">${esc(f.label)}</label>
        ${f.type === 'select' ? `<select id="m-${f.key}" style="width:100%">
          ${(f.options || []).map((o) => `<option value="${esc(o.value)}" ${String(o.value) === String(f.value) ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}
        </select>`
        : `<input id="m-${f.key}" type="${f.type || 'text'}" value="${esc(f.value || '')}"
          ${f.required ? 'required' : ''} style="width:100%"/>`}`).join('')}
      <div class="actions">
        <button class="btn" id="m-cancel">取消</button>
        <button class="btn primary" id="m-ok">确定</button>
      </div>`;
    $('#modal-mask').classList.remove('hidden');
    const close = (v) => { $('#modal-mask').classList.add('hidden'); resolve(v); };
    $('#m-cancel').onclick = () => close(null);
    $('#m-ok').onclick = () => {
      const v = {};
      for (const f of fields) v[f.key] = $('#m-' + f.key).value;
      close(v);
    };
  });
}
function closeModal() { $('#modal-mask').classList.add('hidden'); }

/* ---------------- 潮汐 / 扰动 ---------------- */
function loadTideTable() {
  if (!state.meta) return;
  // 重新拉取以反映修订
  api('/api/meta').then((m) => { state.meta = m; renderTideTable(); });
}
function renderTideTable() {
  const threshold = 3.0; // 样例深水船所需最低潮位（9.5 图示水深 + h - 11.5 >= 1）
  $('#tide-table').innerHTML = `<table class="data"><thead><tr><th>时段</th><th>水位(m)</th><th>深水船 UKC</th><th></th></tr></thead><tbody>
    ${state.meta.tides.map((t) => {
      const h = Number(t.height);
      const ok = h >= threshold;
      return `<tr><td class="mono">${fmtDT(t.start)}–${fmtDT(t.end).slice(6)}</td>
        <td class="tide-h ${ok ? 'deep' : 'shallow'}">${h.toFixed(2)}${t.revised ? ' ✏️' : ''}</td>
        <td>${ok ? '≥1.0m ✅' : '<1.0m ⛔'}</td>
        <td><button class="btn tiny" onclick="reviseTide(${t.id},${h})">修订</button></td></tr>`;
    }).join('')}</tbody></table>`;
}
async function reviseTide(id, oldH) {
  const vals = await openForm({
    title: `修订潮汐时段 #${id}（原水位 ${oldH}m）`,
    fields: [
      { key: 'heightM', label: '新水位（米）', type: 'number', value: oldH.toFixed(2), step: '0.05' },
      { key: 'reason', label: '原因', type: 'text', value: '模拟气象/径流更新' },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/simulation/tides/${id}`, { method: 'PUT', body: { heightM: Number(vals.heightM), reason: vals.reason } });
    const n = (r.impacts || []).length;
    toast(n ? `⚠️ 潮汐已修订，产生 ${n} 条影响项，可到调度工作台重排` : '潮汐已修订，当前无确认计划受影响', n ? 'error' : 'success');
    loadTideTable(); loadDisruptions();
  } catch (e) { showError(e); }
}
async function submitFault() {
  try {
    const r = await api('/api/simulation/crane-faults', {
      method: 'POST',
      body: {
        craneId: Number($('#fault-crane').value),
        startAt: $('#fault-start').value, endAt: $('#fault-end').value,
        reason: $('#fault-reason').value,
      },
    });
    toast(`⚠️ 故障已插入，影响项 ${(r.impacts || []).length} 条`, 'error');
    loadDisruptions();
  } catch (e) { showError(e); }
}
async function loadDisruptions() {
  const el = $('#disruption-list');
  if (!el) return;
  try {
    const list = await api('/api/disruptions');
    if (!list.length) { el.innerHTML = '<p class="hint">暂无扰动记录。</p>'; return; }
    el.innerHTML = list.slice(0, 12).map((d) => `
      <div class="work-card"><div class="wc-title">#${d.id} ${esc(d.dtype)}</div>
        <div class="wc-meta">${esc(d.summary || '')}<br/><span class="mono">${fmtFull(d.created_at)}</span></div>
        <button class="btn tiny" onclick="showDisruption(${d.id})">查看影响项</button>
        <div id="disp-${d.id}" class="hidden"></div>
      </div>`).join('');
  } catch { /* ignore */ }
}
async function showDisruption(id) {
  const box = $('#disp-' + id);
  if (!box.classList.contains('hidden')) { box.classList.add('hidden'); return; }
  const d = await api('/api/disruptions/' + id);
  box.classList.remove('hidden');
  box.innerHTML = (d.impacts || []).map((i) =>
    `<div class="reason" style="color:#8c2b25;margin-top:6px"><b>[${esc(i.code)}]</b> ${esc(i.message)}</div>`).join('')
    || '<p class="hint">无影响项</p>';
}
async function resetScenario() {
  if (!confirm('重置全部数据为题目固定样例？')) return;
  try { await api('/api/admin/reset', { method: 'POST' }); toast('✅ 样例数据已重置', 'success'); location.reload(); }
  catch (e) { showError(e); }
}

/* ---------------- 回放渲染 ---------------- */
async function runReplay(kind) {
  const btn = $('#btn-' + kind);
  ['orderings', 'disruptions', 'full'].forEach((k) => { const b = $('#btn-' + k); if (b) b.disabled = true; });
  $('#replay-summary').innerHTML = '<div class="verdict ok">⏳ 回放执行中，包含真实 TTL 等待（约 10 秒）与并发竞速…</div>';
  $('#replay-result').innerHTML = '';
  try {
    const path = kind === 'full' ? '/api/replays/full' : '/api/replays/' + kind;
    const data = await api(path, { method: 'POST' });
    state.lastReplay = data;
    renderReplay(data);
  } catch (e) { showError(e); $('#replay-summary').innerHTML = '<div class="verdict bad">回放失败：' + esc(e.message) + '</div>'; }
  finally { ['orderings', 'disruptions', 'full'].forEach((k) => { const b = $('#btn-' + k); if (b) b.disabled = false; }); }
}

function renderReplay(data) {
  const pass = data.allPassed;
  $('#replay-summary').innerHTML = `<div class="verdict ${pass ? 'ok' : 'bad'}">
    ${pass ? '✅ 全部断言通过（ALL CHECKS PASSED）' : '❌ 存在未通过断言'}　<button class="btn tiny" onclick="downloadReplay()">下载完整 JSON</button></div>`;

  let html = '';
  const renderBlock = (key, b) => {
    if (!b || !b.checks) return '';
    return `<div class="replay-block">
      <div class="rb-head" onclick="this.nextElementSibling.classList.toggle('hidden')">
        <h3>${b.allPassed ? '✅' : '❌'} ${esc(b.title || key)}</h3><span class="badge ${b.allPassed ? 'CONFIRMED' : 'WITHDRAWN'}">${b.checks.filter((c) => c.pass).length}/${b.checks.length}</span>
      </div>
      <div class="rb-body">
        ${b.checks.map((c) => `<div class="check-row ${c.pass ? 'pass' : 'fail'}">
          <span class="check-icon">${c.pass ? '✓' : '✗'}</span>
          <div><div>${esc(c.name)}</div>
          ${!c.pass ? `<div class="check-detail">期望：${esc(JSON.stringify(c.expected))}<br/>实际：${esc(JSON.stringify(c.actual))}</div>` : ''}
          </div></div>`).join('')}
        <details class="raw"><summary>查看该场景完整 JSON（候选原因 / 时间点 / 资源占用）</summary>
          <pre class="json">${esc(JSON.stringify(b, null, 2))}</pre></details>
      </div></div>`;
  };

  if (data.orderings) {
    for (const [k, v] of Object.entries(data.orderings)) if (k !== 'allPassed') html += renderBlock(k, v);
  }
  if (data.disruptions) {
    for (const [k, v] of Object.entries(data.disruptions)) if (k !== 'allPassed') html += renderBlock(k, v);
  }
  for (const [k, v] of Object.entries(data)) {
    if (['orderings', 'disruptions', 'allPassed'].includes(k)) continue;
    html += renderBlock(k, v);
  }
  $('#replay-result').innerHTML = html;
}
function downloadReplay() {
  const blob = new Blob([JSON.stringify(state.lastReplay, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'replay-result.json'; a.click();
}

/* ================= ⑥ 舱口顺序 · 纵倾 · 岸桥交叉 工作台 ================= */
state.hatch = null;
state.hatchLoaded = false;
state.hatchCaseId = null;

const hn = (v) => (v === null || v === undefined ? '–' : (Math.round(Number(v) * 100) / 100).toString());
const HATCH_STRAT_LABEL = { HATCH_ORDER: '舱序优先', PARALLEL_SIDES: '岸桥并行' };

function hatchOnShow() {
  if (!state.hatchLoaded) hatchLoadSample();
}

async function hatchLoadSample() {
  try {
    state.hatch = await api('/api/hatch/sample');
    state.hatchLoaded = true;
    renderHatchOverview();
  } catch (e) { showError(e); }
}

function hatchDiagram(cfg) {
  const hs = [...(cfg.hatches || [])].sort((a, b) => a.positionM - b.positionM);
  const xs = hs.map((h) => h.positionM);
  const lo = Math.min(...xs, 0), hi = Math.max(...xs, 0);
  const span = (hi - lo) || 1;
  const pos = (x) => 8 + ((x - lo) / span) * 84;
  return `<div class="hatch-diagram">
    <div class="ship-hull">
      <div class="hull-center"></div>
      ${hs.map((h) => `<div class="hatch-node" style="left:${pos(h.positionM)}%">
        <div class="hatch-box"><b>${esc(h.code)}</b><span>${hn(h.cargoT)} t</span></div>
        <div class="hatch-x">x=${hn(h.positionM)} m · 序${h.order}</div>
      </div>`).join('')}
    </div>
    <div class="ship-axis-label">船尾（−）　←　船中 0　→　船首（+）</div>
  </div>`;
}

function hatchGauge(moment, limit) {
  const lim = Math.abs(Number(limit) || 100);
  const raw = 50 + (Number(moment) / lim) * 50;
  const over = raw < 0 || raw > 100;
  const vis = Math.max(2, Math.min(98, raw));
  return `<div class="hatch-gauge ${over ? 'over' : ''}">
    <div class="gauge-track"><div class="gauge-zero"></div>
      <div class="gauge-marker ${over ? 'over' : ''}" style="left:${vis}%"></div>
    </div>
    <div class="gauge-labels"><span>−${lim}</span><span>0</span><span>+${lim}</span></div>
    <div class="gauge-val ${over ? 'bad' : 'ok'}">M = ${hn(moment)} t·m ${over ? '⚠ 越限' : '✓ 在 ±' + lim + ' 内'}</div>
  </div>`;
}

function hatchMoveChips(s) {
  const moves = (s.craneMoves || []).map((m) =>
    `<span class="res-chip hatch-move">🏗️ ${esc(m.craneCode)} → ${esc(m.hatchCode)} · ${hn(m.dischargeT)}t</span>`).join('');
  const idle = (s.idleCranes || []).map((c) =>
    `<span class="res-chip hatch-idle">⏸ ${esc(c)} 空等</span>`).join('');
  return moves + idle || '<span class="hint">无动作</span>';
}

function hatchPlanTable(plan) {
  if (!plan) return '';
  const rows = (plan.slots || []).map((s) => {
    const mCls = Math.abs(s.momentEndTmM) > 100 + 1e-6 ? 'moment-bad' : '';
    return `<tr>
      <td class="mono">${s.slot}<div class="hint">${esc(s.timeLabel || '')}</div></td>
      <td>${hatchMoveChips(s)}</td>
      <td><b>${hn(s.dischargedT)}</b> t</td>
      <td class="mono ${mCls}">${hn(s.momentEndTmM)}</td>
    </tr>`;
  }).join('');
  return `<div>
    <div class="row-between"><h3 class="section-title">${esc(plan.strategyLabel || HATCH_STRAT_LABEL[plan.strategy])}</h3>
      <span class="badge ${plan.feasible ? 'CONFIRMED' : 'WITHDRAWN'}">${plan.feasible ? '可行 · ' + hn(plan.totalDurationHours) + 'h / ' + plan.slotCount + ' 片' : '不可行'}</span>
    </div>
    <table class="data hatch-slots"><thead><tr><th>时间片</th><th>岸桥动作</th><th>本片卸货</th><th>片末力矩 t·m</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="4" class="hint">无时间片</td></tr>'}</tbody></table>
    <p class="hint">空等岸桥片 ${plan.idleCraneSlots} · 占用岸桥片 ${plan.busyCraneSlots} · 容量下界 ${plan.capacityLowerBoundSlots} 片 · ${plan.makespanOptimal ? '✅ 达到时间片容量下界（最优）' : ''}</p>
    ${hatchRejectDetails(plan)}
  </div>`;
}

function hatchRejectDetails(plan) {
  const rs = plan.rejectedSteps || [];
  if (!rs.length) return '<p class="hint">该策略生成过程无被拒候选步骤。</p>';
  return `<details class="raw"><summary>查看该策略 ${rs.length} 个被拒候选步骤（${esc(JSON.stringify(plan.rejectionCodeCounts || {}))}）</summary>
    <table class="data"><thead><tr><th>片</th><th>岸桥</th><th>舱</th><th>尝试量</th><th>拒绝原因</th></tr></thead><tbody>
    ${rs.map((r) => `<tr>
      <td class="mono">${r.slot ?? ''}</td>
      <td>${esc(r.craneCode || '–')}</td>
      <td>${esc(r.hatchCode || '–')}</td>
      <td>${r.attemptedDischargeT != null ? hn(r.attemptedDischargeT) + 't' : '–'}</td>
      <td>${(r.violations || []).map((v) => `<div><span class="code">[${esc(v.code)}]</span> ${esc(v.message)}</div>`).join('')}
          <div class="hint">${esc(r.explanation || '')}</div></td>
    </tr>`).join('')}
    </tbody></table></details>`;
}

function renderHatchOverview() {
  const d = state.hatch;
  const el = $('#hatch-overview');
  if (!d || !el) return;
  const cfg = d.config;
  const fh = d.firstHour || {};
  const td = d.totalDuration || {};
  const rj = d.rejectedSteps || {};
  const probes = d.constraintProbes || [];

  el.innerHTML = `
    <div class="hatch-conclusions">
      ${(d.conclusions || []).map((c) => `<div class="verdict ok" style="margin:6px 0">✅ ${esc(c)}</div>`).join('')}
    </div>

    <div class="grid two-col" style="margin-top:10px">
      <div class="card inner">
        <h3 class="section-title">船舶舱位布置</h3>
        ${hatchDiagram(cfg)}
      </div>
      <div class="card inner">
        <h3 class="section-title">并行首小时片末力矩（H1+H3 各 10t）</h3>
        ${hatchGauge(fh.parallelSides ? fh.parallelSides.momentEndTmM : 0, cfg.momentLimitTmM)}
        <h3 class="section-title" style="margin-top:14px">舱序优先首小时片末力矩（仅 H2 10t）</h3>
        ${hatchGauge(fh.hatchOrder ? fh.hatchOrder.momentEndTmM : 0, cfg.momentLimitTmM)}
      </div>
    </div>

    <div class="hatch-kpis">
      <div class="kpi"><div class="kpi-v">${hn(fh.parallelSides ? fh.parallelSides.dischargedT : 0)} t</div><div class="kpi-l">并行策略首小时卸货<br/>QC1→H1 + QC2→H3</div></div>
      <div class="kpi warn"><div class="kpi-v">${hn(fh.hatchOrder ? fh.hatchOrder.dischargedT : 0)} t</div><div class="kpi-l">舱序优先首小时卸货<br/>仅 H2，另一桥空等</div></div>
      <div class="kpi"><div class="kpi-v">${hn(td.parallelHours)} h</div><div class="kpi-l">并行总时长（${td.parallelSlots} 片）</div></div>
      <div class="kpi warn"><div class="kpi-v">${hn(td.hatchOrderHours)} h</div><div class="kpi-l">舱序优先总时长（${td.hatchOrderSlots} 片）</div></div>
      <div class="kpi bad"><div class="kpi-v">${rj.hatchOrderSearchRejectedSteps}</div><div class="kpi-l">舱序策略被拒候选步骤<br/>并行策略 ${rj.parallelSearchRejectedSteps} 个</div></div>
      <div class="kpi bad"><div class="kpi-v">${rj.constraintProbeRejected}/${rj.constraintProbeTotal}</div><div class="kpi-l">违规试排被拒 / 试排总数<br/>含 ${rj.constraintProbeAccepted} 个合法正对照</div></div>
    </div>
    <p class="hint">${esc(td.note || '')}</p>

    <div class="grid two-col hatch-plan-grid">
      ${hatchPlanTable(d.hatchOrderPlan)}
      ${hatchPlanTable(d.parallelPlan)}
    </div>

    <h3 class="section-title">“被拒步骤”约束探针（同舱 / 交叉 / 纵倾 / 速率）</h3>
    <table class="data">
      <thead><tr><th>试排步骤</th><th>期望</th><th>实际</th><th>试算力矩</th><th>违例</th></tr></thead>
      <tbody>${probes.map((p) => `<tr class="${p.asExpected ? '' : 'row-bad'}">
        <td>${esc(p.title)}</td>
        <td>${p.expectedAccepted ? '应通过' : '应拒绝'}</td>
        <td><span class="badge ${p.accepted ? 'CONFIRMED' : 'WITHDRAWN'}">${p.accepted ? '通过' : '拒绝'}</span>
            ${p.asExpected ? '✓' : '✗ 不符合预期'}</td>
        <td class="mono">${p.momentAfterTmM != null ? hn(p.momentAfterTmM) : '–'}</td>
        <td>${(p.violations || []).map((v) => `<div><span class="code">[${esc(v.code)}]</span> ${esc(v.message)}</div>`).join('') || '<span class="hint">无</span>'}</td>
      </tr>`).join('')}</tbody>
    </table>`;
}

/* ---------- 持久化案例 / 实测 / 回执 / 重排 ---------- */

async function hatchCreateCase() {
  try {
    const d = await api('/api/hatch/cases', { method: 'POST', body: { strategy: 'PARALLEL_SIDES' } });
    state.hatchCaseId = d.id;
    $('#hatch-case-card').style.display = '';
    hatchRenderCase(d);
    toast('✅ 已建立样例作业案例 #' + d.id, 'success');
    $('#hatch-case-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) { showError(e); }
}

async function hatchRefreshCase() {
  if (!state.hatchCaseId) return;
  try {
    const d = await api('/api/hatch/cases/' + state.hatchCaseId);
    hatchRenderCase(d);
  } catch (e) { showError(e); }
}

function hatchRenderCase(d) {
  state.hatchCaseId = d.id;
  $('#hatch-case-code').textContent = '#' + d.id + ' ' + d.code;
  const cfg = d.config, st = d.state || {};
  const plans = d.plans || [];
  const latest = plans[plans.length - 1];
  const prov = latest && latest.provenance ? latest.provenance : null;

  const hatchRows = cfg.hatches.map((h) => {
    const actual = Number((st.actualDischargedT || {})[h.code] || 0);
    const remain = Number((st.remainingCargoT || {})[h.code] != null ? st.remainingCargoT[h.code] : Math.max(0, h.cargoT - actual));
    const pct = Math.min(100, (actual / h.cargoT) * 100);
    return `<tr><td><b>${esc(h.code)}</b><div class="hint">x=${hn(h.positionM)}m</div></td>
      <td>${hn(h.cargoT)}</td><td>${hn(actual)}</td><td>${hn(remain)}</td>
      <td style="min-width:140px"><div class="prog"><div class="prog-bar" style="width:${pct}%"></div></div></td></tr>`;
  }).join('');

  const pending = st.pendingBallastReceipts || [];
  const ballastMap = st.receivedBallastChangeT || {};

  $('#hatch-case').innerHTML = `
    ${prov ? hatchProvBanner(prov, latest) : ''}
    <div class="row gap wrap" style="margin:8px 0">
      <button class="btn primary tiny" onclick="hatchAddCargo()">＋ 登记实测卸货量</button>
      <button class="btn tiny" onclick="hatchAddBallastOrder()">＋ 下达压载计划</button>
      <button class="btn success tiny" onclick="hatchReplan()">↻ 按最新实测重排剩余作业</button>
    </div>

    <div class="grid two-col">
      <div>
        <h3 class="section-title">各舱实测完成 / 剩余</h3>
        <table class="data"><thead><tr><th>舱</th><th>应卸 t</th><th>实测 t</th><th>剩余 t</th><th>完成</th></tr></thead>
          <tbody>${hatchRows}</tbody></table>
        ${hatchGauge(st.actualMomentTmM, cfg.momentLimitTmM)}
        <p class="hint">实测当前力矩 <b>${hn(st.actualMomentTmM)}</b> t·m；
           若把未回执压载的计划值错误计入，会得到 <b>${hn(st.momentIfPendingPlannedCountedTmM)}</b> t·m（仅对照，不用于重排）。</p>
      </div>
      <div>
        <h3 class="section-title">压载回执状态</h3>
        ${cfg.ballastTanks.map((t) => `<p class="kv">${esc(t.code)}（x=${hn(t.positionM)}m，±${hn(t.maxChangeTPerHour)}t/h）：
           已回执实测累计 <b>${hn(ballastMap[t.code] || 0)}</b> t</p>`).join('')}
        ${pending.length ? `<div class="verdict bad">⏳ ${pending.length} 条压载指令回执未到（迟到）：
           ${pending.map((p) => `${esc(p.orderCode)} ${esc(p.tankCode)} 计划 ${hn(p.plannedChangeT)}t`).join('；')}
           <br/>其计划值<b>未计入</b>实测状态与重排。</div>`
           : '<div class="verdict ok">✅ 没有待回执压载指令；所有压载量均来自实测回执。</div>'}
        <h3 class="section-title" style="margin-top:12px">只增台账（实测 / 指令 / 回执，DB 触发器禁止改删）</h3>
        ${hatchRecordsTable(d.records || [])}
      </div>
    </div>

    <h3 class="section-title">计划版本（${plans.length}）</h3>
    ${plans.slice().reverse().map((p, idx) => {
      const plan = p.plan || {};
      const open = idx === 0 ? 'open' : '';
      return `<details class="raw" ${open} style="margin-bottom:8px">
        <summary><b>v${p.versionNo}</b> · ${p.basis === 'REPLANNED_FROM_ACTUALS' ? '实测重排' : '初始计划'} ·
          ${esc(HATCH_STRAT_LABEL[p.strategy] || p.strategy)} · ${esc(p.summary || '')}</summary>
        ${hatchPlanTable(plan)}
      </details>`;
    }).join('')}`;
}

function hatchProvBanner(prov, latest) {
  const pend = prov.pendingBallastReceipts || [];
  return `<div class="verdict ${pend.length ? 'bad' : 'ok'}" style="margin:8px 0">
    <b>重排 v${latest.versionNo} 数据来源：${esc(prov.basis)}</b> ·
    usedPlannedAsActual=<b>${String(prov.usedPlannedAsActual)}</b> ·
    实测力矩 ${hn(prov.actualMomentTmM)} t·m ·
    重排初始力矩 ${hn(prov.remainingConfigInitialMomentTmM)} t·m
    ${prov.warning ? `<br/>⚠️ ${esc(prov.warning)}` : '<br/>✅ 无待回执压载，采用的全部是真实回执值。'}
  </div>`;
}

function hatchRecordsTable(records) {
  if (!records.length) return '<p class="hint">暂无台账记录。</p>';
  const typeLabel = { CARGO_ACTUAL: '卸货实测', BALLAST_ORDER: '压载计划', BALLAST_RECEIPT: '压载回执' };
  return `<table class="data"><thead><tr><th>#</th><th>类型</th><th>舱/压载舱</th><th>量 t</th><th>状态/操作</th><th>时间</th></tr></thead><tbody>
    ${records.slice().reverse().map((r) => {
      let qty = '–';
      if (r.recType === 'CARGO_ACTUAL') qty = `+${hn(r.deltaT)}（累计 ${hn(r.actualT)}）`;
      if (r.recType === 'BALLAST_ORDER') qty = `计划 ${hn(r.plannedT)}`;
      if (r.recType === 'BALLAST_RECEIPT') qty = `实测 ${hn(r.actualT)}`;
      const linkedReceipt = r.recType === 'BALLAST_ORDER' &&
        records.some((x) => x.recType === 'BALLAST_RECEIPT' && x.orderCode === r.orderCode);
      let status;
      if (r.recType === 'BALLAST_ORDER') {
        status = linkedReceipt ? '<span class="badge CONFIRMED">已回执</span>'
          : `<span class="badge STAGED">待回执/迟到</span>
             <button class="btn success tiny" onclick="hatchReceipt(${r.id},'${esc(r.orderCode)}',${Number(r.plannedT)})">提交回执</button>`;
      } else if (r.recType === 'BALLAST_RECEIPT') {
        status = `<span class="badge CONFIRMED">已回执${r.late ? '·迟到' : ''}</span>`;
      } else {
        status = esc(r.operatorName || '实测');
      }
      return `<tr><td class="mono">${r.id}</td><td>${typeLabel[r.recType] || r.recType}</td>
        <td>${esc(r.hatchCode || r.tankCode || '–')}</td><td class="mono">${qty}</td>
        <td>${status}</td><td class="mono hint">${esc(String(r.createdAt || '').replace('T', ' ').slice(5, 19))}</td></tr>`;
    }).join('')}</tbody></table>`;
}

async function hatchAddCargo() {
  const d = await api('/api/hatch/cases/' + state.hatchCaseId);
  const vals = await openForm({
    title: '登记实测卸货增量（写入只增台账）',
    fields: [
      { key: 'hatchCode', label: '舱口', type: 'select', value: 'H1',
        options: d.config.hatches.map((h) => ({ value: h.code, label: `${h.code}（应卸 ${h.cargoT}t / x=${h.positionM}m）` })) },
      { key: 'deltaT', label: '本次实测完成量（吨）', type: 'number', value: '10' },
      { key: 'operatorName', label: '操作人', value: state.user ? state.user.displayName : 'dispatch' },
      { key: 'note', label: '备注', value: '现场实测' },
    ],
  });
  if (!vals) return;
  try {
    const updated = await api(`/api/hatch/cases/${state.hatchCaseId}/cargo-actuals`, {
      method: 'POST',
      body: { hatchCode: vals.hatchCode, deltaT: Number(vals.deltaT), operatorName: vals.operatorName, note: vals.note },
    });
    hatchRenderCase(updated); toast('✅ 实测卸货量已登记', 'success');
  } catch (e) { showError(e); }
}

async function hatchAddBallastOrder() {
  const d = await api('/api/hatch/cases/' + state.hatchCaseId);
  if (!d.config.ballastTanks.length) return toast('当前构型没有压载舱', 'error');
  const vals = await openForm({
    title: '下达压载调整计划（此时仅为计划值）',
    fields: [
      { key: 'tankCode', label: '压载舱', type: 'select', value: d.config.ballastTanks[0].code,
        options: d.config.ballastTanks.map((t) => ({ value: t.code, label: `${t.code}（x=${t.positionM}m，±${t.maxChangeTPerHour}t/h）` })) },
      { key: 'plannedChangeT', label: '计划增减（吨，正=加压 / 负=排压）', type: 'number', value: '10' },
      { key: 'note', label: '备注', value: '压载计划指令' },
    ],
  });
  if (!vals) return;
  try {
    const updated = await api(`/api/hatch/cases/${state.hatchCaseId}/ballast-orders`, {
      method: 'POST',
      body: { tankCode: vals.tankCode, plannedChangeT: Number(vals.plannedChangeT), note: vals.note },
    });
    hatchRenderCase(updated); toast('✅ 压载计划已下达；回执到达前不会计为实测', 'success');
  } catch (e) { showError(e); }
}

async function hatchReceipt(orderId, orderCode, planned) {
  const vals = await openForm({
    title: `提交压载实测回执 ${orderCode}`,
    fields: [
      { key: 'actualChangeT', label: `回执实测增减（吨；原计划 ${planned}，可不同）`, type: 'number', value: String(planned) },
      { key: 'late', label: '回执是否迟到', type: 'select', value: 'true',
        options: [{ value: 'true', label: '是，回执迟到' }, { value: 'false', label: '否，按时回执' }] },
      { key: 'operatorName', label: '回执/操作人', value: state.user ? state.user.displayName : 'crane' },
    ],
  });
  if (!vals) return;
  try {
    const updated = await api(`/api/hatch/cases/${state.hatchCaseId}/ballast-receipts`, {
      method: 'POST',
      body: { orderId, actualChangeT: Number(vals.actualChangeT), late: vals.late === 'true', operatorName: vals.operatorName },
    });
    hatchRenderCase(updated);
    toast(Number(vals.actualChangeT) === Number(planned) ? '✅ 回执已登记' : '✅ 回执已登记：重排将采用实测 ' + vals.actualChangeT + 't，而非计划 ' + planned + 't', 'success');
  } catch (e) { showError(e); }
}

async function hatchReplan() {
  try {
    const r = await api(`/api/hatch/cases/${state.hatchCaseId}/replan`, { method: 'POST', body: {} });
    hatchRenderCase(r.caseDetail);
    const n = (r.provenance.pendingBallastReceipts || []).length;
    toast(n ? `⚠️ 已按实测重排 v${r.versionNo}；${n} 条迟到压载计划被排除，未冒充实测`
            : `✅ 已按实测重排 v${r.versionNo}`, n ? 'error' : 'success');
  } catch (e) { showError(e); }
}

async function hatchResetAll() {
  if (!confirm('清空全部舱口工作台案例/台账/计划？不影响泊位主数据。')) return;
  try {
    await api('/api/hatch/reset', { method: 'POST' });
    state.hatchCaseId = null;
    $('#hatch-case-card').style.display = 'none';
    toast('已清空舱口案例');
  } catch (e) { showError(e); }
}

async function hatchVerify() {
  const btn = $('#btn-hatch-verify');
  btn.disabled = true;
  $('#hatch-verify').innerHTML = '<div class="verdict ok">⏳ 正在运行舱口特性 15 项端到端自验（含真实 PostgreSQL 触发器）…</div>';
  try {
    const d = await api('/api/hatch/verify', { method: 'POST' });
    $('#hatch-verify').innerHTML = `<div class="verdict ${d.allPassed ? 'ok' : 'bad'}">
        ${d.allPassed ? '✅ 舱口特性自验全部通过 ' + d.passed + '/' + d.total : '❌ 存在未通过项 ' + d.passed + '/' + d.total}
      </div>
      <div class="replay-block"><div class="rb-body">
        ${(d.checks || []).map((c) => `<div class="check-row ${c.pass ? 'pass' : 'fail'}">
          <span class="check-icon">${c.pass ? '✓' : '✗'}</span><div>${esc(c.name)}
          ${c.pass ? '' : `<div class="check-detail">期望：${esc(JSON.stringify(c.expected))} 实际：${esc(JSON.stringify(c.actual))}</div>`}</div>
        </div>`).join('')}
      </div></div>`;
    // verify 会 TRUNCATE 舱口案例，前端当前案例已失效
    state.hatchCaseId = null;
    $('#hatch-case-card').style.display = 'none';
    state.hatchLoaded = false;
    hatchLoadSample();
  } catch (e) {
    showError(e);
    $('#hatch-verify').innerHTML = '<div class="verdict bad">自验调用失败：' + esc(e.message) + '</div>';
  } finally { btn.disabled = false; }
}

boot();
