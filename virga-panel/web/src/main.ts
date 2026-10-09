import QRCode from 'qrcode';

type Tab = 'overview' | 'bot' | 'groups' | 'custom' | 'settings';

interface CustomCommand {
  key: string; template: string; description: string; permission: 'MEMBER' | 'ADMIN' | 'ROOT';
  enabled: boolean; panel: boolean; requireBinding: boolean; cooldownSeconds: number; showFeedback: boolean;
}
let customCommands: CustomCommand[] = [];
let customDirty = false;
let customSaving = false;

interface Overview {
  pluginVersion: string; platform: string; minecraftVersion: string; javaVersion: string;
  uptimeSeconds: number; onlinePlayers: number; maxPlayers: number; qqConnected: boolean;
  botConfigured: boolean; allowedGroups: number; panelPort: number; serverAddress: string;
  queues: { worker: number; render: number; state: number; scheduled: number };
  pendingRestart: string[];
}
interface BotInfo { enabled: boolean; configured: boolean; maskedAppId?: string; connected: boolean; qrConnectEnabled: boolean; restartRequired: boolean }
interface QrStatus { phase: 'IDLE' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'; qrUrl?: string; message: string }
interface GroupEntry { id: string; display: string; allowed: boolean; note?: string; lastSeenMillis?: number; lastMessage?: string; name?: string; memberCount?: number; purpose: 'PLAYER' | 'MANAGEMENT' }
interface CommandOption {
  command: string; allowed: boolean; panel: boolean; label: string; description: string;
  category: string; permission: string; available: boolean; highRisk: boolean; defaultPanel: boolean;
  management: boolean; playerMutation: boolean;
}
interface CommandEditor { purpose: 'PLAYER' | 'MANAGEMENT'; commands: CommandOption[]; sync: { state: string; message: string; updatedAt: number } }
let commandEditor: CommandEditor | undefined;
let savedCommandState: { purpose: 'PLAYER' | 'MANAGEMENT'; allowed: Set<string> } | null = null;
let editingGroup: GroupEntry | undefined;
let draggedCommand: number | undefined;
let commandsSaving = false;
let commandDraftDirty = false;
interface SettingValue { key: string; label: string; group: string; description: string; value: boolean; effect: string; riskWarning?: string | null }
interface SaveResult { ok: boolean; message: string; restartRequired: boolean }

const TOKEN_KEY = 'virga-panel-token';
const $ = <T extends HTMLElement = HTMLElement>(selector: string) => document.querySelector<T>(selector)!;
const escape = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

const pages: Record<Tab, { eyebrow: string; title: string; subtitle: string }> = {
  overview: { eyebrow: 'VIRGA AT A GLANCE', title: '运行概览', subtitle: '在线人数、QQ 连接与后台队列状态。' },
  bot: { eyebrow: 'QQ BOT CONNECTION', title: '连接 QQ 机器人', subtitle: '扫码授权或手工填写机器人凭据，保存后立即连接，无需重启服务器。' },
  groups: { eyebrow: 'QQ GROUPS', title: '群管理', subtitle: '允许的群、群用途，以及每个群的指令开关、入口与顺序。' },
  custom: { eyebrow: 'CUSTOM COMMANDS', title: '自定义指令', subtitle: '命令模板只在面板中编辑；群里只显示入口和说明。' },
  settings: { eyebrow: 'SETTINGS', title: '功能设置', subtitle: '开关立即生效；指令仍按各群设置与用户权限检查。' }
};

let token = sessionStorage.getItem(TOKEN_KEY);
let currentTab: Tab = 'overview';
let overviewTimer: number | undefined;
let qrTimer: number | undefined;

// ---------- Helpers ----------

class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

async function api<T>(path: string, method: 'GET' | 'POST' = 'GET', data?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (method === 'POST') headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, body: method === 'POST' ? JSON.stringify(data ?? {}) : undefined, credentials: 'same-origin', signal });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (response.status === 401 && path !== '/api/login') {
    signOut('登录已失效，请重新登录');
    throw new ApiError(body.message ?? '请先登录', 401);
  }
  if (!response.ok) throw new ApiError(body.message ?? `请求失败（${response.status}）`, response.status);
  return body as T;
}

function toast(message: string, error = false) {
  const element = $('#toast');
  element.textContent = message;
  element.className = `visible${error ? ' error' : ''}`;
  window.clearTimeout(Number(element.dataset.timer));
  element.dataset.timer = String(window.setTimeout(() => { element.className = ''; }, 3200));
}

function failure(error: unknown) {
  if (error instanceof ApiError && error.status === 401) return;
  toast(error instanceof Error ? error.message : String(error), true);
}

function openDialog(html: string) {
  const dialog = $<HTMLDialogElement>('#dialog');
  dialog.innerHTML = html;
  dialog.showModal();
}

function closeDialog() {
  const dialog = $<HTMLDialogElement>('#dialog');
  if (dialog.open) dialog.close();
}

function duration(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  return `${minutes} 分钟`;
}

function relative(millis?: number): string {
  if (!millis) return '暂无消息记录';
  const seconds = Math.max(0, Math.floor((Date.now() - millis) / 1000));
  if (seconds < 60) return '刚刚发过消息';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前发过消息`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前发过消息`;
  return `${Math.floor(seconds / 86400)} 天前发过消息`;
}

function stopTimers() {
  window.clearInterval(overviewTimer);
  window.clearInterval(qrTimer);
  overviewTimer = undefined;
  qrTimer = undefined;
  groupWatch++; // ends any running group long poll loop
  groupWatchAbort?.abort();
  groupWatchAbort = undefined;
}

function signOut(message?: string) {
  token = null;
  sessionStorage.removeItem(TOKEN_KEY);
  stopTimers();
  renderLogin();
  if (message) toast(message, true);
}

// ---------- Login ----------

function renderLogin() {
  $('#app').innerHTML = `<div class="login-screen"><section class="panel login-card">
    <a class="brand" href="#"><img src="/virga-logo.png" alt=""><span>Virga<small>Virga 管理面板</small></span></a>
    <span class="eyebrow">SIGN IN</span><h1>登录</h1>
    <p class="lead">输入管理面板密码。面板只在服务器本机监听，请通过 SSH 隧道访问。</p>
    <form id="login-form"><label>管理面板密码<input name="password" type="password" autocomplete="current-password" required autofocus></label>
    <p id="login-error" class="inline-error" role="alert"></p><button class="primary" type="submit">登录</button></form>
    <p class="login-hint">首次启用时密码只在服务器控制台显示一次。忘记密码可在控制台执行 <code>virga passwd 新密码</code> 直接设置，或 <code>virga passwd</code> 重新随机生成。</p>
  </section></div>`;
  $<HTMLInputElement>('#login-form input[name=password]').focus();
}

async function login(form: HTMLFormElement) {
  const button = form.querySelector('button')!;
  button.disabled = true;
  try {
    const result = await api<{ token: string }>('/api/login', 'POST', { password: (new FormData(form).get('password') as string) ?? '' });
    token = result.token;
    sessionStorage.setItem(TOKEN_KEY, token);
    renderApp();
  } catch (error) {
    $('#login-error').textContent = error instanceof Error ? error.message : '登录失败';
  } finally {
    button.disabled = false;
  }
}

// ---------- Shell ----------

function renderApp() {
  const tabs: [Tab, string][] = [['overview', '运行概览'], ['bot', '机器人连接'], ['groups', '群管理'], ['custom', '自定义指令'], ['settings', '功能设置']];
  $('#app').innerHTML = `<header class="site-header">
      <a class="brand" href="#overview" data-tab="overview"><img src="/virga-logo.png" alt=""><span>Virga<small>Virga 管理面板</small></span></a>
      <nav aria-label="主导航">${tabs.map(([id, label]) => `<button data-tab="${id}">${label}</button>`).join('')}</nav>
      <div class="header-actions"><button class="status-pill" id="connection-status" data-tab="bot">正在读取状态</button><button class="quiet" data-action="logout">退出登录</button></div>
    </header>
    <main><div class="page-heading"><div><span class="eyebrow" id="page-eyebrow"></span><h1 id="page-title"></h1><p id="page-subtitle"></p></div></div>
      <section data-page="overview"></section><section data-page="bot" hidden></section><section data-page="groups" hidden></section><section data-page="custom" hidden></section><section data-page="settings" hidden></section>
    </main>
    <footer class="site-footer"><span>Virga 管理面板 <span class="version" id="footer-version"></span></span><span>仅限本机访问 · 请通过 SSH 隧道连接</span></footer>`;
  const initial = (location.hash.replace('#', '') || 'overview') as Tab;
  showTab(initial in pages ? initial : 'overview');
}

function showTab(tab: Tab) {
  if (currentTab === 'custom' && customDirty && tab === 'custom') return;
  if (currentTab === 'custom' && customDirty && tab !== 'custom' && !window.confirm('自定义指令还未保存，确定离开吗？')) return;
  currentTab = tab;
  stopTimers();
  if (location.hash !== `#${tab}`) history.replaceState(null, '', `#${tab}`);
  document.querySelectorAll<HTMLButtonElement>('nav button').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll<HTMLElement>('[data-page]').forEach(p => { p.hidden = p.dataset.page !== tab; });
  const page = pages[tab];
  $('#page-eyebrow').textContent = page.eyebrow;
  $('#page-title').textContent = page.title;
  $('#page-subtitle').textContent = page.subtitle;
  ({ overview: loadOverview, bot: loadBot, groups: loadGroups, custom: loadCustomCommands, settings: loadSettings })[tab]().catch(failure);
  if (tab === 'overview') overviewTimer = window.setInterval(() => loadOverview().catch(failure), 10000);
  if (tab === 'groups') watchGroups(++groupWatch).catch(() => undefined);
}

function updateStatusPill(connected: boolean, configured: boolean) {
  const pill = document.querySelector<HTMLElement>('#connection-status');
  if (!pill) return;
  pill.className = `status-pill${connected ? ' online' : ''}`;
  pill.textContent = connected ? 'QQ 已连接' : configured ? 'QQ 未连接' : 'QQ 尚未配置';
}

// ---------- Overview ----------

async function loadOverview() {
  const o = await api<Overview>('/api/overview');
  if (currentTab !== 'overview') return;
  updateStatusPill(o.qqConnected, o.botConfigured);
  $('#footer-version').textContent = `v${o.pluginVersion}`;
  const qq = o.qqConnected ? ['已连接', 'good'] : o.botConfigured ? ['未连接', 'warn'] : ['未配置', 'warn'];
  $('[data-page=overview]').innerHTML = `<div class="stat-grid">
      <article class="panel stat-card"><span class="stat-label">运行平台</span><div class="stat-value">${escape(o.platform)}</div><span class="stat-note">Minecraft ${escape(o.minecraftVersion)}</span></article>
      <article class="panel stat-card"><span class="stat-label">在线玩家</span><div class="stat-value">${o.onlinePlayers}<small class="stat-note"> / ${o.maxPlayers}</small></div><span class="stat-note">当前在线人数</span></article>
      <article class="panel stat-card"><span class="stat-label">QQ 连接</span><div class="stat-value ${qq[1]}">${qq[0]}</div><span class="stat-note">允许的群 ${o.allowedGroups} 个</span></article>
      <article class="panel stat-card"><span class="stat-label">已运行</span><div class="stat-value">${escape(duration(o.uptimeSeconds))}</div><span class="stat-note">自服务器启动</span></article>
    </div>
    <div class="overview-columns">
      <section class="panel info-panel"><span class="eyebrow">NEEDS A RESTART</span><h2>待重启生效的设置</h2>
        ${o.pendingRestart.length ? `<ul class="restart-list">${o.pendingRestart.map(r => `<li>${escape(r)}</li>`).join('')}</ul>` : '<p class="all-good">没有待重启生效的设置。</p>'}
      </section>
      <section class="panel info-panel"><span class="eyebrow">RUNTIME</span><h2>运行信息</h2><dl class="kv">
        <dt>Virga 版本</dt><dd>${escape(o.pluginVersion)}</dd><dt>Java</dt><dd>${escape(o.javaVersion)}</dd>
        <dt>面板端口</dt><dd>${o.panelPort}</dd><dt>后台任务队列</dt><dd>${o.queues.worker}</dd>
        <dt>图片渲染队列</dt><dd>${o.queues.render}</dd><dt>状态写入队列</dt><dd>${o.queues.state}</dd><dt>定时任务</dt><dd>${o.queues.scheduled}</dd>
      </dl></section>
    </div>`;
}

// ---------- Bot connection ----------

async function loadBot() {
  const info = await api<BotInfo>('/api/bot');
  if (currentTab !== 'bot') return;
  updateStatusPill(info.connected, info.configured);
  const status = await api<QrStatus>('/api/bot/qr/status');
  $('[data-page=bot]').innerHTML = `${info.restartRequired ? '<div class="restart-banner">机器人凭据已更新。QQ 客户端现在没在运行，需要完整重启服务器后才会用新凭据连接；在此之前手机 QQ 会一直显示“连接中”。</div>' : ''}
    <div class="connection-layout">
      <section class="panel pairing-panel"><span class="eyebrow">QQ CONNECTION</span><h2>扫码连接</h2>
        <div id="pairing-area"></div><p id="pairing-message"></p>
        ${info.qrConnectEnabled
          ? '<div class="pair-actions"><button id="pair-start" class="primary" data-action="pair">生成授权二维码</button><button id="pair-cancel" class="quiet" data-action="cancel-pair" hidden>取消扫码</button></div>'
          : '<p class="small-note">扫码连接已在功能设置中关闭，请在右侧手工填写。</p>'}
      </section>
      <section class="panel manual-panel"><span class="step">MANUAL</span><h2>手工填写凭据</h2>
        <div class="group-meta"><span class="badge ${info.configured ? 'on' : 'warn'}">${info.configured ? '已配置' : '未配置'}</span>
          <span class="badge ${info.connected ? 'on' : ''}">${info.connected ? '已连接' : '未连接'}</span>
          ${info.maskedAppId ? `<span class="badge">AppID ${escape(info.maskedAppId)}</span>` : ''}</div>
        <form id="credentials-form">
          <label>AppID<input name="appId" inputmode="numeric" autocomplete="off" required placeholder="QQ 开放平台的机器人 AppID"></label>
          <label>AppSecret <span class="optional">留空表示保持原来的 Secret</span><input name="secret" type="password" autocomplete="new-password" placeholder="出于安全考虑，已保存的 Secret 不会显示"></label>
          <p id="credentials-error" class="inline-error" role="alert"></p>
          <div class="dialog-actions"><button class="primary" type="submit">保存凭据</button></div>
        </form>
        <p class="section-note">凭据只保存在服务器的 config.yml，面板不会把 Secret 发回浏览器。保存后会立即用新凭据连接 QQ，机器人已在线时也会直接切换，不用重启服务器。</p>
        <p class="small-note">换成另一个机器人后，同一个群的 OpenID 会变化：请把新机器人拉进群，并在游戏内用 /virga group add 重新接入。</p>
      </section>
    </div>`;
  renderQr(status);
  if (status.phase === 'WAITING') startQrPolling();
}

async function renderQr(status: QrStatus) {
  const area = document.querySelector<HTMLElement>('#pairing-area');
  if (!area) return;
  const waiting = status.phase === 'WAITING' && status.qrUrl;
  if (waiting) {
    const image = await QRCode.toDataURL(status.qrUrl!, { width: 288, margin: 2, errorCorrectionLevel: 'M' });
    area.innerHTML = `<img class="qrcode" src="${escape(image)}" alt="QQ 授权二维码">`;
  } else {
    area.innerHTML = `<div class="qr-placeholder"><p>${status.phase === 'COMPLETED' ? '扫码完成' : '点击下方按钮生成二维码'}</p></div>`;
  }
  const message = document.querySelector<HTMLElement>('#pairing-message');
  if (message) message.textContent = status.phase === 'IDLE' ? '' : status.message;
  document.querySelector<HTMLElement>('#pair-start')?.toggleAttribute('hidden', Boolean(waiting));
  document.querySelector<HTMLElement>('#pair-cancel')?.toggleAttribute('hidden', !waiting);
}

function startQrPolling() {
  window.clearInterval(qrTimer);
  qrTimer = window.setInterval(async () => {
    try {
      const status = await api<QrStatus>('/api/bot/qr/status');
      await renderQr(status);
      if (status.phase !== 'WAITING') {
        window.clearInterval(qrTimer);
        if (status.phase === 'COMPLETED') {
          await loadBot();
          const info = await api<BotInfo>('/api/bot');
          toast(info.restartRequired ? '扫码成功，凭据已保存；请完整重启服务器后生效' : '扫码成功，正在用新凭据连接 QQ');
          if (!info.restartRequired) window.setTimeout(() => { if (currentTab === 'bot') loadBot().catch(failure); }, 5000);
        }
      }
    } catch (error) {
      window.clearInterval(qrTimer);
      failure(error);
    }
  }, 2000);
}

async function saveCredentials(form: HTMLFormElement) {
  const data = new FormData(form);
  const button = form.querySelector('button')!;
  button.disabled = true;
  try {
    const result = await api<SaveResult>('/api/bot/credentials', 'POST', { appId: String(data.get('appId') ?? '').trim(), secret: String(data.get('secret') ?? '').trim() });
    toast(result.message);
    await loadBot();
  } catch (error) {
    $('#credentials-error').textContent = error instanceof Error ? error.message : '保存失败';
  } finally {
    button.disabled = false;
  }
}

// ---------- Groups ----------

let groups: GroupEntry[] = [];
let groupActionRunning = false;
let groupWatch = 0;
let groupWatchAbort: AbortController | undefined;

/**
 * Long poll: the server answers the moment a group joins, speaks or is changed, so new groups
 * show up instantly. Stops when the page is left; waits while a dialog or action is open.
 */
async function watchGroups(generation: number) {
  const controller = new AbortController();
  groupWatchAbort = controller;
  let version = (await api<{ version: number }>('/api/groups/changes?since=-1', 'GET', undefined, controller.signal)).version;
  while (generation === groupWatch && currentTab === 'groups' && token) {
    const result = await api<{ version: number; changed: boolean }>(`/api/groups/changes?since=${version}`, 'GET', undefined, controller.signal)
      .catch(async error => {
        if (error instanceof ApiError && error.status === 401) throw error;
        if (controller.signal.aborted) return { version, changed: false };
        await new Promise(resolve => window.setTimeout(resolve, 3000));
        return { version, changed: false };
      });
    if (generation !== groupWatch || currentTab !== 'groups') break;
    version = result.version;
    // Poll timeouts also revalidate metadata so quiet groups don't keep a renamed title forever.
    if (!$<HTMLDialogElement>('#dialog').open && !groupActionRunning) await loadGroups().catch(failure);
  }
}

async function loadGroups() {
  groups = await api<GroupEntry[]>('/api/groups');
  if (currentTab !== 'groups') return;
  $('[data-page=groups]').innerHTML = groups.length
    ? `<div class="group-list-toolbar"><p class="section-note">用途保存后即时生效。群名会自动更新，备注不会被 QQ 群名覆盖。</p><button type="button" class="secondary" data-action="refresh-group-info">刷新群名</button></div><div class="panel group-list">${groups.map((g, index) => `
        <article class="group-card">
          <div><h3>${escape(g.note || g.name || '正在获取群名…')}</h3>
            <p class="group-subtitle">${g.note && g.name ? `QQ 群名：${escape(g.name)} · ` : ''}${g.memberCount != null ? `${g.memberCount} 人 · ` : ''}<code>${escape(g.display)}</code></p>
            ${g.lastMessage ? `<p class="last-message">最近消息：${escape(g.lastMessage)}</p>` : ''}
            <div class="group-meta"><span class="badge ${g.allowed ? 'on' : ''}">${g.allowed ? '已允许' : '未允许'}</span><span class="badge">${g.purpose === 'MANAGEMENT' ? '管理群' : '玩家群'}</span><span class="small-note">${escape(relative(g.lastSeenMillis))}</span></div></div>
          <div class="group-actions"><button class="secondary" data-action="commands" data-index="${index}" ${g.allowed ? '' : 'disabled'}>指令面板</button><button class="secondary" data-action="members" data-index="${index}">成员与权限</button><button class="secondary" data-action="note" data-index="${index}">备注</button>
            <button class="${g.allowed ? 'secondary' : 'primary'}" data-action="toggle-group" data-index="${index}">${g.allowed ? '取消允许' : '允许该群'}</button></div>
        </article>`).join('')}</div>`
    : `<div class="panel empty-state"><h3>暂无群</h3><p>把机器人拉进群并 @机器人 发一条消息，群就会出现在这里。<br>也可以在游戏内用 /virga group add 接入。</p></div>`;
}

async function toggleGroup(group: GroupEntry) {
  groupActionRunning = true;
  try {
    if (group.allowed && !window.confirm(`确定取消允许「${group.note || group.display}」吗？该群就不能再使用 Virga 的指令了。`)) return;
    const result = await api<SaveResult>('/api/groups', 'POST', { id: group.id, allowed: !group.allowed });
    toast(result.message);
    await loadGroups();
  } finally {
    groupActionRunning = false;
  }
}

interface GroupMember { openId: string; display: string; name?: string; seenAtMillis?: number; root: boolean; administrator: boolean }
interface GroupMembers { group: string; members: GroupMember[]; trustQqGroupRoles: boolean }
const MEMBERS_PAGE = 20;
let membersGroup: GroupEntry | undefined;
let membersData: GroupMembers | undefined;
let membersShown = MEMBERS_PAGE;
let membersQuery = '';

async function membersDialog(group: GroupEntry, keepView = false) {
  membersGroup = group;
  membersData = await api<GroupMembers>(`/api/groups/members?id=${encodeURIComponent(group.id)}`);
  if (!keepView) { membersShown = MEMBERS_PAGE; membersQuery = ''; }
  openDialog(`<div class="dialog-heading"><div><span class="eyebrow">MEMBERS</span><h2 id="dialog-title">成员与权限</h2></div><button class="quiet" data-action="close-dialog" aria-label="关闭">×</button></div>
    <p class="dialog-description">${escape(group.note || group.name || group.display)}</p>
    <p class="small-note">QQ 机器人拿不到 QQ 号，这里只列出在本群说过话或被 @ 过的人；想添加的人先在群里发一句话。本群管理员只在本群有效，可使用管理指令；超级管理员在所有群有效，并且可以远程执行服务器命令。${membersData.trustQqGroupRoles ? '<br>已开启“信任 QQ 群主/管理员”：QQ 群主和群管理员自动视为本群管理员。' : ''}</p>
    <input id="member-search" class="member-search" type="search" placeholder="按昵称或 ID 搜索" value="${escape(membersQuery)}" autocomplete="off">
    <div id="member-list" class="member-list soft-scroll"></div>`);
  renderMembers();
  const search = $<HTMLInputElement>('#member-search');
  search.addEventListener('input', () => { membersQuery = search.value.trim(); membersShown = MEMBERS_PAGE; renderMembers(); });
}

/** ROOT and administrators first, then the most recently seen; at most [membersShown] rows. */
function renderMembers() {
  const data = membersData;
  if (!data) return;
  const query = membersQuery.toLowerCase();
  const matches = data.members
    .filter(m => !query || (m.name ?? '').toLowerCase().includes(query) || m.openId.toLowerCase().includes(query))
    .sort((a, b) => Number(b.root || b.administrator) - Number(a.root || a.administrator) || (b.seenAtMillis ?? 0) - (a.seenAtMillis ?? 0));
  const rows = matches.slice(0, membersShown).map(m => `
    <div class="member-row">
      <div class="member-info"><b>${escape(m.name || '未记录昵称')}</b>
        <small><code>${escape(m.display)}</code>${m.seenAtMillis ? ` · 记录于${escape(relative(m.seenAtMillis))}` : ''}</small>
        <span class="member-badges">${m.root ? '<span class="badge on">超级管理员</span>' : ''}${m.administrator ? '<span class="badge on">本群管理员</span>' : ''}</span></div>
      <div class="member-actions">
        <button type="button" class="secondary" data-action="member-admin" data-user="${escape(m.openId)}" data-name="${escape(m.name || m.display)}" data-on="${m.administrator ? '0' : '1'}">${m.administrator ? '取消管理员' : '设为管理员'}</button>
        <button type="button" class="secondary" data-action="member-root" data-user="${escape(m.openId)}" data-name="${escape(m.name || m.display)}" data-on="${m.root ? '0' : '1'}">${m.root ? '取消超级管理员' : '设为超级管理员'}</button>
      </div>
    </div>`).join('');
  const more = matches.length > membersShown
    ? `<button type="button" class="quiet member-more" data-action="members-more">再显示 ${Math.min(MEMBERS_PAGE, matches.length - membersShown)} 人（已显示 ${membersShown} / 共 ${matches.length} 人）</button>`
    : (matches.length > MEMBERS_PAGE ? `<p class="small-note member-count">共 ${matches.length} 人</p>` : '');
  $('#member-list').innerHTML = rows + more || `<p class="small-note">${data.members.length
    ? '没有找到匹配的成员。'
    : '还没有记录到这个群的成员。让对方在群里发一句话后再打开这里。'}</p>`;
}

async function setMemberRole(target: HTMLElement, kind: 'admin' | 'root') {
  const group = membersGroup;
  if (!group) return;
  const user = target.dataset.user!;
  const on = target.dataset.on === '1';
  const name = target.dataset.name ?? '';
  if (kind === 'root' && on && !window.confirm(`超级管理员拥有全部权限，包括远程执行服务器命令。确定把「${name}」设为超级管理员吗？`)) return;
  target.setAttribute('disabled', '');
  try {
    const result = kind === 'admin'
      ? await api<SaveResult>('/api/groups/admins', 'POST', { group: group.id, user, administrator: on })
      : await api<SaveResult>('/api/roots', 'POST', { user, root: on });
    toast(result.message);
    await membersDialog(group, true);
  } catch (error) {
    failure(error);
    target.removeAttribute('disabled');
  }
}

function noteDialog(group: GroupEntry) {
  openDialog(`<div class="dialog-heading"><div><span class="eyebrow">GROUP NOTE</span><h2 id="dialog-title">群备注</h2></div><button class="quiet" data-action="close-dialog" aria-label="关闭">×</button></div>
    <p class="dialog-description"><code>${escape(group.display)}</code></p>
    <form id="note-form" data-id="${escape(group.id)}"><label>备注 <span class="optional">仅面板可见，最多 40 字</span><input name="note" maxlength="40" value="${escape(group.note ?? '')}" placeholder="例如：玩家主群"></label>
    <p id="dialog-error" class="inline-error" role="alert"></p><div class="dialog-actions"><button type="button" class="quiet" data-action="close-dialog">取消</button><button class="primary" type="submit">保存备注</button></div></form>`);
}

async function saveNote(form: HTMLFormElement) {
  try {
    await api<SaveResult>('/api/groups', 'POST', { id: form.dataset.id, note: String(new FormData(form).get('note') ?? '') });
    closeDialog();
    toast('备注已保存');
    await loadGroups();
  } catch (error) {
    $('#dialog-error').textContent = error instanceof Error ? error.message : '保存失败';
  }
}

// ---------- Settings ----------

async function commandsDialog(group: GroupEntry) {
  const editor = await api<CommandEditor>(`/api/groups/commands?id=${encodeURIComponent(group.id)}`);
  editingGroup = group;
  commandEditor = editor;
  savedCommandState = snapshotCommands(editor);
  commandsSaving = false;
  commandDraftDirty = false;
  openDialog(`<form id="commands-form"><header class="dialog-heading command-heading"><div><span class="eyebrow">GROUP COMMANDS</span><h2 id="dialog-title">${escape(group.note || group.name || group.display)} · 指令面板</h2></div><button type="button" class="quiet" data-action="close-dialog" aria-label="关闭">×</button></header>
    <fieldset class="command-fields"><div class="command-toolbar"><div class="command-purpose-field"><span class="control-label">群用途</span><div id="command-purpose" class="purpose-switch" role="group" aria-label="群用途">
      <button type="button" data-action="command-purpose" data-purpose="PLAYER">玩家群</button><button type="button" data-action="command-purpose" data-purpose="MANAGEMENT">管理群</button></div></div>
      <div class="command-template-field"><span class="control-label">快捷设置</span><button type="button" class="secondary" data-action="command-template">恢复本用途默认开关</button></div><p id="command-purpose-hint" class="small-note"></p></div>
    <p class="command-intro small-note">拖动手柄或用箭头排序。允许使用与面板显示分别控制；隐藏入口仍可手动输入，用户原有权限不变。</p>
    <div class="command-layout soft-scroll" tabindex="0" aria-label="指令配置区域"><div id="command-rows" class="soft-scroll" tabindex="0" aria-label="可滚动的指令列表"></div><aside class="command-preview panel"><div class="command-preview-heading"><span class="eyebrow">QQ MENU</span><h3>群里的快捷菜单</h3></div><div id="command-preview" class="soft-scroll" tabindex="0" aria-label="可滚动的 QQ 面板预览"></div><div class="command-preview-footer"><p class="small-note">帮助沿用此顺序，并按发言者权限过滤。</p><p id="command-sync" class="small-note" role="status"></p><button type="button" class="secondary" data-action="command-sync">重新同步 QQ 面板</button></div></aside></div></fieldset>
    <footer class="command-footer"><div class="command-feedback"><p id="command-draft-state" role="status"></p><p id="dialog-error" class="inline-error" role="alert"></p></div><div class="dialog-actions"><button type="button" class="quiet" data-action="close-dialog">关闭</button><button class="primary" type="submit">保存并同步</button></div></footer></form>`);
  $('#dialog').classList.add('command-dialog');
  renderCommandRows();
  renderCommandPurpose();
  renderCommandDraftState();
  $('#command-sync').textContent = editor.sync.message;
}

/** High-risk setting: a management command opened in a player group (any group admin can then use it there). */
function commandHighRisk(c: CommandOption): boolean {
  return commandEditor!.purpose === 'PLAYER' && c.management;
}

function snapshotCommands(editor: CommandEditor): { purpose: CommandEditor['purpose']; allowed: Set<string> } {
  return { purpose: editor.purpose, allowed: new Set(editor.commands.filter(c => c.allowed).map(c => c.command)) };
}

/** High-risk commands this draft opens that were not already open for the same purpose. */
function newlyOpenedHighRisk(editor: CommandEditor): CommandOption[] {
  const saved = savedCommandState;
  return editor.commands.filter(c => c.allowed && commandHighRisk(c) &&
    !(saved && saved.purpose === editor.purpose && saved.allowed.has(c.command)));
}

function renderCommandRows() {
  $('#command-rows').innerHTML = commandEditor!.commands.map((c, i) => `<article class="command-row" data-command-index="${i}">
    <div class="command-row-heading"><span class="drag-handle" draggable="true" title="拖动排序" aria-label="拖动 ${escape(c.command)} 排序">⠿</span><b>/${escape(c.command)}</b><span class="badge">${escape(c.permission)}</span>${commandHighRisk(c) ? '<span class="badge danger">高危</span>' : ''}${c.available ? '' : '<span class="badge warn">总开关已关闭</span>'}
      <span class="command-arrows"><button type="button" class="quiet" data-action="command-up" data-index="${i}" ${i === 0 ? 'disabled' : ''} aria-label="上移 ${escape(c.command)}">↑</button><button type="button" class="quiet" data-action="command-down" data-index="${i}" ${i === commandEditor!.commands.length - 1 ? 'disabled' : ''} aria-label="下移 ${escape(c.command)}">↓</button></span></div>
    <div class="command-options"><label class="command-toggle"><span class="switch"><input type="checkbox" data-command-field="allowed" ${c.allowed ? 'checked' : ''}><span aria-hidden="true"></span></span><span>本群允许使用</span></label>
      <label class="command-toggle"><span class="switch"><input type="checkbox" data-command-field="panel" ${c.panel ? 'checked' : ''} ${!c.allowed ? 'disabled' : ''}><span aria-hidden="true"></span></span><span>面板显示</span></label></div>
    <div class="command-text"><label>展示名称<input data-command-field="label" value="${escape(c.label)}" ${c.category === '旧版功能' ? 'readonly' : ''} maxlength="128"></label><label>简短说明<input data-command-field="description" value="${escape(c.description)}" maxlength="200"></label></div>
    <small class="small-note command-rule-hint${commandHighRisk(c) ? ' danger' : ''}">${commandHighRisk(c) ? '高危：在玩家群开放后，本群的管理员可以直接在群里使用这条管理指令' : !c.available ? '功能总开关已关闭，开启后才会进入 QQ 菜单' : '原指令仍可使用，展示名称也可作为本群入口'}</small>
    </article>`).join('');
  renderCommandPreview();
}

function renderCommandPurpose() {
  document.querySelectorAll<HTMLButtonElement>('[data-action=command-purpose]').forEach(button => {
    const selected = button.dataset.purpose === commandEditor!.purpose;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
  $('#command-purpose-hint').textContent = commandEditor!.purpose === 'MANAGEMENT'
    ? '默认开放管理指令；远程执行还需开启功能总开关，默认仅超级管理员可用。'
    : '默认只开放玩家功能；管理指令也能在这里打开，但属于高危设置，保存时需要确认。';
}

function renderCommandDraftState() {
  const name = commandEditor!.purpose === 'MANAGEMENT' ? '管理群' : '玩家群';
  $('#command-draft-state').textContent = commandsSaving ? '正在保存…'
    : commandDraftDirty ? `当前选择：${name} · 有未保存的修改，请点击右侧保存`
    : `已保存用途：${name} · 本地规则已生效，QQ 发布状态见预览区`;
  $('#command-draft-state').classList.toggle('pending', commandDraftDirty);
}

function commandChanged() {
  commandDraftDirty = true;
  renderCommandDraftState();
}

function setCommandPurpose(purpose: CommandEditor['purpose']) {
  if (!commandEditor || commandsSaving || commandEditor.purpose === purpose) return;
  commandEditor.purpose = purpose;
  renderCommandPurpose();
  applyCommandTemplate();
}

function renderCommandPreview() {
  const visible = commandEditor!.commands.filter(c => c.allowed && c.panel && c.available);
  $('#command-preview').innerHTML = `<p class="small-note">${visible.length} / 20 个入口</p>` + (visible.map(c =>
    `<div class="preview-command"><b>${escape(c.label)}</b><small>${escape(c.description)}</small>${c.management ? '<small>管理员入口</small>' : ''}</div>`).join('') || '<p class="small-note">没有显示的入口；允许的文字指令仍可使用。</p>');
}

function moveCommand(from: number, to: number) {
  if (!commandEditor || commandsSaving || from === to || to < 0 || to >= commandEditor.commands.length) return;
  const [c] = commandEditor.commands.splice(from, 1);
  commandEditor.commands.splice(to, 0, c);
  renderCommandRows();
  commandChanged();
}

function applyCommandTemplate() {
  if (!commandEditor || commandsSaving) return;
  commandEditor!.commands.forEach(c => {
    // Defaults per purpose: players get player features, management groups get management commands.
    c.allowed = commandEditor!.purpose === 'PLAYER' ? !c.management
      : c.management || ['帮助', '服务器状态', '在线列表', '查在线', '服务器地址'].includes(c.command);
    c.panel = c.allowed && c.defaultPanel;
  });
  renderCommandRows();
  commandChanged();
}

async function saveCommands(form: HTMLFormElement) {
  const button = form.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const group = editingGroup!;
  const editor = commandEditor!;
  const risky = newlyOpenedHighRisk(editor);
  if (risky.length > 0 && !window.confirm(
    `高危设置确认\n\n你正在把下面的管理指令开放到玩家群：\n${risky.map(c => `/${c.command}`).join('、')}\n\n` +
    '开放后，本群的管理员（执行命令等默认仅超级管理员）可以直接在玩家群里使用这些指令，群里所有人都能看到结果；' +
    '加管理、执行命令、强制解绑一旦误用会直接影响服务器和玩家账号。\n\n确定要开放吗？')) {
    $('#dialog-error').textContent = '已取消保存：没有开放高危指令。';
    return;
  }
  const submitted = { id: group.id, purpose: editor.purpose, confirmHighRisk: risky.length > 0,
    commands: editor.commands.map(({ command, allowed, panel, label, description }) => ({ command, allowed, panel, label, description })) };
  commandsSaving = true;
  form.querySelector<HTMLFieldSetElement>('.command-fields')!.disabled = true;
  button.disabled = true;
  renderCommandDraftState();
  $('#dialog-error').textContent = '';
  try {
    const result = await api<SaveResult>('/api/groups/commands', 'POST', submitted);
    const confirmed = await api<CommandEditor>(`/api/groups/commands?id=${encodeURIComponent(group.id)}`);
    if (confirmed.purpose !== submitted.purpose || submitted.commands.some(c => {
      const saved = confirmed.commands.find(s => s.command === c.command);
      return !saved || saved.allowed !== c.allowed || saved.panel !== c.panel || saved.label !== c.label || (c.description.trim() !== '' && saved.description !== c.description);
    })) throw new Error('保存后的规则与当前选择不一致，请重新打开本群配置检查一下。');
    if (commandEditor !== editor) return; // The dialog was closed/reopened while saving.
    commandEditor = confirmed;
    savedCommandState = snapshotCommands(confirmed);
    commandDraftDirty = false;
    commandsSaving = false;
    renderCommandPurpose();
    renderCommandRows();
    renderCommandDraftState();
    toast(result.message);
    await pollCommandSync(group.id);
    await loadGroups();
  } catch (error) {
    if ($('#dialog').hasAttribute('open') && (commandEditor === editor || editingGroup === group)) $('#dialog-error').textContent = error instanceof Error ? error.message : '保存失败';
  } finally {
    button.disabled = false;
    form.querySelector<HTMLFieldSetElement>('.command-fields')!.disabled = false;
    if (editingGroup === group) { commandsSaving = false; renderCommandDraftState(); }
  }
}

async function pollCommandSync(groupId: string) {
  const editing = commandEditor;
  for (let i = 0; i < 8; i++) {
    const editor = await api<CommandEditor>(`/api/groups/commands?id=${encodeURIComponent(groupId)}`);
    if (!$('#dialog').hasAttribute('open') || editingGroup?.id !== groupId || commandEditor !== editing) return;
    $('#command-sync').textContent = editor.sync.message;
    if (editor.sync.state !== 'pending') return;
    await new Promise(resolve => window.setTimeout(resolve, 1000));
  }
}

async function loadCustomCommands() {
  const commands = await api<CustomCommand[]>('/api/custom-commands');
  if (currentTab !== 'custom') return;
  customCommands = commands; customDirty = false;
  renderCustomCommands();
}

function renderCustomCommands() {
  $('[data-page=custom]').innerHTML = `<form id="custom-form" class="custom-form">
    <section class="panel custom-intro"><h2>自定义指令</h2>
      <p class="small-note">玩家发送“/触发词 参数”。模板以服务器控制台权限执行，请只开放可信任的操作。管理员与超级管理员指令默认只在管理群开放；各群的允许、展示名称和排序仍在“群管理”配置。</p>
      <p class="small-note"><code>{player}</code> / <code>{name}</code> 为本群已验证主账号；<code>{params}</code> 为全部参数；<code>{0}</code> 至 <code>{9}</code> 为第 1 至第 10 个参数。公开指令的参数仅允许游戏 ID、数字或英文词，不接受选择器与脚本。</p>
    </section>
    <div class="custom-list">${customCommands.map((c, i) => `<section class="panel custom-card" data-custom-index="${i}">
      <div class="custom-heading"><h2>指令 ${i + 1}</h2><button type="button" class="quiet" data-action="custom-remove" data-index="${i}" ${customSaving ? 'disabled' : ''}>删除</button></div>
      <div class="custom-fields">
        <label>触发词<input data-custom-field="key" value="${escape(c.key)}" maxlength="14" required placeholder="例如：签到"></label>
        <label>谁能使用<select data-custom-field="permission">${[['ROOT', '超级管理员'], ['ADMIN', '管理员'], ['MEMBER', '群成员']].map(([v, label]) => `<option value="${v}" ${c.permission === v ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
        <label>公开说明<input data-custom-field="description" value="${escape(c.description)}" maxlength="30" required></label>
        <label>冷却时间（秒）<input data-custom-field="cooldownSeconds" type="number" min="1" max="300" value="${c.cooldownSeconds}" required></label>
      </div>
      <label class="custom-template">私有命令模板<textarea data-custom-field="template" rows="2" maxlength="2048" required placeholder="例如：give {player} minecraft:bread 1">${escape(c.template)}</textarea></label>
      <div class="custom-switches">${([['enabled', '允许执行'], ['panel', '默认展示入口'], ['requireBinding', '需要验证绑定'], ['showFeedback', '显示过滤后的反馈']] as const).map(([field, label]) => `<div class="switch-row"><b>${label}</b><label class="switch" aria-label="${label}"><input type="checkbox" data-custom-field="${field}" ${c[field] ? 'checked' : ''}><span aria-hidden="true"></span></label></div>`).join('')}</div>
      <p class="small-note">默认只发简短回执，不展示命令模板。详细反馈可能包含其他模组的信息，开启前请先在测试群验证。</p>
    </section>`).join('')}</div>
    <div class="panel custom-save"><span>${customDirty ? '有未保存的修改' : `${customCommands.length} / 32 条指令`}</span><div class="dialog-actions"><button type="button" class="secondary" data-action="custom-add" ${customCommands.length >= 32 || customSaving ? 'disabled' : ''}>添加指令</button><button class="primary" type="submit" ${customSaving ? 'disabled' : ''}>${customSaving ? '正在保存…' : '保存指令'}</button></div></div>
  </form>`;
}

async function saveCustomCommands() {
  if (customSaving) return;
  if (customCommands.some(c => c.permission === 'MEMBER') && !window.confirm('群成员指令以控制台权限执行。请确认模板和参数不会授予不应开放的能力，继续保存吗？')) return;
  customSaving = true;
  try {
    const result = await api<SaveResult>('/api/custom-commands', 'POST', { commands: customCommands });
    customDirty = false; toast(result.message); await loadCustomCommands();
  } finally { customSaving = false; if (currentTab === 'custom') renderCustomCommands(); }
}

async function loadSettings() {
  const [settings, overview] = await Promise.all([api<SettingValue[]>('/api/settings'), api<Overview>('/api/overview')]);
  if (currentTab !== 'settings') return;
  const grouped = new Map<string, SettingValue[]>();
  settings.forEach(s => grouped.set(s.group, [...(grouped.get(s.group) ?? []), s]));
  $('[data-page=settings]').innerHTML = `<div class="settings-layout">
      <section class="panel settings-panel"><span class="eyebrow">FEATURES</span>
        ${[...grouped.entries()].map(([group, items]) => `<div class="setting-group"><h3>${escape(group)}</h3>${items.map(s => `
          <div class="switch-row"><div><b>${escape(s.label)}</b>${s.riskWarning ? ' <span class="badge danger">高危</span>' : ''}<small>${escape(s.description)} · ${escape(s.effect)}</small></div>
            <label class="switch" aria-label="${escape(s.label)}"><input type="checkbox" data-setting="${escape(s.key)}" ${s.riskWarning ? `data-risk="${escape(s.riskWarning)}"` : ''} ${s.value ? 'checked' : ''}><span aria-hidden="true"></span></label></div>`).join('')}</div>`).join('')}
      </section>
      <aside class="side-stack">
        <section class="panel side-panel"><span class="eyebrow">SERVER ADDRESS</span><h2>服务器地址</h2>
          <p class="small-note">群里发送“服务器地址”时回复这里填写的地址；留空则不回复。</p>
          <form id="address-form"><label>地址 <span class="optional">可带端口，不能有空格</span><input name="address" maxlength="128" autocomplete="off" placeholder="例如 mc.example.com:25565" value="${escape(overview.serverAddress ?? '')}"></label>
            <div class="dialog-actions"><button class="secondary" type="submit">保存地址</button></div></form>
        </section>
        <section class="panel side-panel"><span class="eyebrow">PANEL PORT</span><h2>面板端口</h2>
          <p class="small-note">当前端口 ${overview.panelPort}。修改后面板会在新端口重新监听，请用新地址（及对应的 SSH 隧道）重新打开。</p>
          <form id="port-form"><label>新端口<input name="port" type="number" min="1024" max="65535" required value="${overview.panelPort}"></label>
            <div class="dialog-actions"><button class="secondary" type="submit">更改端口</button></div></form>
        </section>
        <section class="panel side-panel"><span class="eyebrow">PASSWORD</span><h2>修改登录密码</h2>
          <form id="password-form"><label>当前密码<input name="current" type="password" autocomplete="current-password" required></label>
            <label>新密码 <span class="optional">至少 12 个字符</span><input name="next" type="password" autocomplete="new-password" minlength="12" required></label>
            <label>再次输入新密码<input name="confirm" type="password" autocomplete="new-password" minlength="12" required></label>
            <p id="password-error" class="inline-error" role="alert"></p>
            <div class="dialog-actions"><button class="primary" type="submit">修改密码</button></div></form>
        </section>
      </aside>
    </div>`;
}

async function toggleSetting(input: HTMLInputElement) {
  const key = input.dataset.setting!;
  const value = input.checked;
  const risk = value ? input.dataset.risk : undefined;
  if (risk && !window.confirm(`高危设置确认

${risk}

确定要打开吗？`)) {
    input.checked = false;
    return;
  }
  input.disabled = true;
  try {
    const result = await api<SaveResult>('/api/settings', 'POST', { values: { [key]: value }, confirmHighRisk: Boolean(risk) });
    toast(result.message);
  } catch (error) {
    input.checked = !value;
    failure(error);
  } finally {
    input.disabled = false;
  }
}

async function saveAddress(form: HTMLFormElement) {
  const address = String(new FormData(form).get('address') ?? '').trim();
  if (/\s/.test(address)) { toast('服务器地址不能有空格', true); return; }
  const result = await api<SaveResult>('/api/server-address', 'POST', { address });
  toast(result.message);
}

async function changePort(form: HTMLFormElement) {
  const port = Number(new FormData(form).get('port'));
  if (!window.confirm(`确定把面板改到端口 ${port} 吗？当前页面会失去连接。`)) return;
  const result = await api<SaveResult & { port: number }>('/api/panel/port', 'POST', { port });
  const url = `http://127.0.0.1:${result.port}/`;
  openDialog(`<div class="dialog-heading"><div><span class="eyebrow">PORT CHANGED</span><h2 id="dialog-title">面板已换到新端口</h2></div></div>
    <p class="dialog-description">${escape(result.message)}</p>
    <p class="small-note">SSH 隧道示例：<code>ssh -N -L 127.0.0.1:${result.port}:127.0.0.1:${result.port} 你的服务器</code></p>
    <div class="dialog-actions"><a class="primary" href="${escape(url)}">打开新地址</a></div>`);
}

async function changePassword(form: HTMLFormElement) {
  const data = new FormData(form);
  const next = String(data.get('next') ?? '');
  if (next !== String(data.get('confirm') ?? '')) {
    $('#password-error').textContent = '两次输入的新密码不一致';
    return;
  }
  try {
    await api<{ ok: boolean }>('/api/password', 'POST', { current: String(data.get('current') ?? ''), next });
    token = null;
    sessionStorage.removeItem(TOKEN_KEY);
    stopTimers();
    renderLogin();
    toast('密码已修改，请用新密码重新登录');
  } catch (error) {
    $('#password-error').textContent = error instanceof Error ? error.message : '修改失败';
  }
}

// ---------- Events ----------

async function action(name: string, target: HTMLElement) {
  const index = Number(target.dataset.index);
  switch (name) {
    case 'logout':
      await api('/api/logout', 'POST').catch(() => undefined);
      signOut();
      toast('已退出登录');
      break;
    case 'pair': {
      target.setAttribute('disabled', '');
      try {
        await renderQr(await api<QrStatus>('/api/bot/qr/start', 'POST'));
        startQrPolling();
      } finally {
        target.removeAttribute('disabled');
      }
      break;
    }
    case 'cancel-pair':
      window.clearInterval(qrTimer);
      await renderQr(await api<QrStatus>('/api/bot/qr/cancel', 'POST'));
      break;
    case 'toggle-group': await toggleGroup(groups[index]); break;
    case 'note': noteDialog(groups[index]); break;
    case 'members': await membersDialog(groups[index]); break;
    case 'member-admin': await setMemberRole(target, 'admin'); break;
    case 'members-more': membersShown += MEMBERS_PAGE; renderMembers(); break;
    case 'member-root': await setMemberRole(target, 'root'); break;
    case 'commands': await commandsDialog(groups[index]); break;
    case 'refresh-group-info': {
      target.setAttribute('disabled', '');
      try { const result = await api<SaveResult>('/api/groups/refresh', 'POST'); toast(result.message); await loadGroups(); }
      finally { target.removeAttribute('disabled'); }
      break;
    }
    case 'command-up': moveCommand(index, index - 1); break;
    case 'command-down': moveCommand(index, index + 1); break;
    case 'command-template': applyCommandTemplate(); break;
    case 'command-purpose': setCommandPurpose(target.dataset.purpose as CommandEditor['purpose']); break;
    case 'command-sync': await api('/api/groups/commands/sync', 'POST'); await pollCommandSync(editingGroup!.id); break;
    case 'close-dialog': closeDialog(); break;
  }
}

document.addEventListener('click', event => {
  const customAction = (event.target as Element).closest<HTMLElement>('[data-action^="custom-"]');
  if (customAction) {
    event.preventDefault();
    if (customSaving) return;
    if (customAction.dataset.action === 'custom-add' && customCommands.length < 32) {
      customCommands.push({ key: '', template: '', description: '自定义指令', permission: 'ROOT', enabled: true, panel: false, requireBinding: true, cooldownSeconds: 5, showFeedback: false });
    } else if (customAction.dataset.action === 'custom-remove' && window.confirm('删除这条自定义指令？保存后生效。')) {
      customCommands.splice(Number(customAction.dataset.index), 1);
    } else return;
    customDirty = true; renderCustomCommands(); return;
  }
  const target = (event.target as Element).closest<HTMLElement>('[data-tab], [data-action]');
  if (!target) return;
  if (target.dataset.tab) {
    event.preventDefault();
    showTab(target.dataset.tab as Tab);
  } else if (target.dataset.action) {
    event.preventDefault();
    action(target.dataset.action, target).catch(failure);
  }
});

document.addEventListener('submit', event => {
  const form = event.target as HTMLFormElement;
  event.preventDefault();
  const handlers: Record<string, (form: HTMLFormElement) => Promise<void>> = {
    'login-form': login, 'credentials-form': saveCredentials, 'note-form': saveNote,
    'address-form': saveAddress, 'port-form': changePort, 'password-form': changePassword, 'commands-form': saveCommands,
    'custom-form': saveCustomCommands
  };
  handlers[form.id]?.(form).catch(failure);
});

document.addEventListener('change', event => {
  const input = event.target as HTMLInputElement;
  if (input.dataset.setting) toggleSetting(input).catch(failure);
});

document.addEventListener('input', event => {
  const input = event.target as HTMLInputElement;
  const customField = input.dataset.customField;
  const customRow = input.closest<HTMLElement>('[data-custom-index]');
  if (customField && customRow && !customSaving) {
    const c = customCommands[Number(customRow.dataset.customIndex)];
    if (['enabled', 'panel', 'requireBinding', 'showFeedback'].includes(customField)) {
      c[customField as 'enabled' | 'panel' | 'requireBinding' | 'showFeedback'] = input.checked;
    } else if (customField === 'cooldownSeconds') c.cooldownSeconds = Number(input.value);
    else if (customField === 'permission') c.permission = input.value as CustomCommand['permission'];
    else c[customField as 'key' | 'template' | 'description'] = input.value;
    customDirty = true;
    const status = document.querySelector('.custom-save > span');
    if (status) status.textContent = '有未保存的修改';
    return;
  }
  const field = input.dataset.commandField;
  const row = input.closest<HTMLElement>('[data-command-index]');
  if (!commandEditor || commandsSaving || !row || !field) return;
  const c = commandEditor.commands[Number(row.dataset.commandIndex)];
  if (field === 'allowed' || field === 'panel') c[field] = input.checked;
  else if (field === 'label' || field === 'description') c[field] = input.value;
  if (!c.allowed) c.panel = false;
  if (field === 'allowed') {
    const panelInput = row.querySelector<HTMLInputElement>('[data-command-field=panel]')!;
    panelInput.disabled = !c.allowed;
    panelInput.checked = c.panel;
  }
  renderCommandPreview();
  commandChanged();
});

document.addEventListener('dragstart', event => {
  const handle = (event.target as HTMLElement).closest('.drag-handle');
  const row = handle?.closest<HTMLElement>('[data-command-index]');
  if (!row) return;
  draggedCommand = Number(row.dataset.commandIndex);
  event.dataTransfer?.setData('text/plain', String(draggedCommand));
});
document.addEventListener('dragover', event => {
  if (draggedCommand !== undefined && (event.target as HTMLElement).closest('[data-command-index]')) event.preventDefault();
});
document.addEventListener('drop', event => {
  const row = (event.target as HTMLElement).closest<HTMLElement>('[data-command-index]');
  if (row && draggedCommand !== undefined) {
    event.preventDefault();
    moveCommand(draggedCommand, Number(row.dataset.commandIndex));
  }
  draggedCommand = undefined;
});
document.addEventListener('dragend', () => { draggedCommand = undefined; });
$<HTMLDialogElement>('#dialog').addEventListener('close', () => {
  $('#dialog').classList.remove('command-dialog');
  commandEditor = undefined;
  editingGroup = undefined;
  commandsSaving = false;
  commandDraftDirty = false;
  if (currentTab === 'groups' && token) loadGroups().catch(failure);
});

window.addEventListener('hashchange', () => {
  const tab = location.hash.replace('#', '') as Tab;
  if (token && tab in pages && tab !== currentTab) showTab(tab);
});

// ---------- Start ----------

if (token) {
  api('/api/session').then(renderApp).catch(() => undefined);
} else {
  renderLogin();
}
