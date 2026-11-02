"use strict";

const STRINGS = {
  "title": [
    "Mika JP · 会話デスク",
    "Mika JP · 会話デスク"
  ],
  "heading": [
    "会話デスク",
    "会話デスク"
  ],
  "displayLanguage": [
    "表示言語",
    "表示言語"
  ],
  "browserLanguage": [
    "日本語",
    "日本語"
  ],
  "newChat": [
    "新しい会話",
    "新しい会話"
  ],
  "recentChats": [
    "最近の会話",
    "最近の会話"
  ],
  "conversations": [
    "会話一覧",
    "会話一覧"
  ],
  "currentChat": [
    "現在の会話",
    "現在の会話"
  ],
  "openNav": [
    "会話一覧を開く",
    "会話一覧を開く"
  ],
  "closeNav": [
    "会話一覧を閉じる",
    "会話一覧を閉じる"
  ],
  "myPhone": [
    "自分のスマホ",
    "自分のスマホ"
  ],
  "networkPhone": [
    "ネットワーク接続のスマホ",
    "ネットワーク接続のスマホ"
  ],
  "adbPhone": [
    "ADB接続のスマホ",
    "ADB接続のスマホ"
  ],
  "connectionDetails": [
    "接続の詳細",
    "接続の詳細"
  ],
  "connectedPhone": [
    "接続中のスマホ",
    "接続中のスマホ"
  ],
  "taskService": [
    "タスクサービス",
    "タスクサービス"
  ],
  "phoneState": [
    "スマホの状態",
    "スマホの状態"
  ],
  "chooseDevice": [
    "スマホを選ぶ",
    "スマホを選ぶ"
  ],
  "usePhone": [
    "このスマホを使う",
    "このスマホを使う"
  ],
  "refreshNow": [
    "更新する",
    "更新する"
  ],
  "updated": [
    "更新: {time}",
    "更新: {time}"
  ],
  "autoUpdate": [
    "自動更新",
    "自動更新"
  ],
  "connecting": [
    "接続中",
    "接続中"
  ],
  "connected": [
    "接続済み",
    "接続済み"
  ],
  "online": [
    "オンライン",
    "オンライン"
  ],
  "offline": [
    "未接続",
    "未接続"
  ],
  "choosing": [
    "スマホを選んでください",
    "スマホを選んでください"
  ],
  "unlocked": [
    "ロック解除済み",
    "ロック解除済み"
  ],
  "locked": [
    "ロック中",
    "ロック中"
  ],
  "screenOff": [
    "画面は消灯中",
    "画面は消灯中"
  ],
  "unknown": [
    "不明",
    "不明"
  ],
  "helperOnline": [
    "ヘルパーは稼働中",
    "ヘルパーは稼働中"
  ],
  "helperOffline": [
    "ヘルパーは停止中",
    "ヘルパーは停止中"
  ],
  "recentHint": [
    "直近50件のリモートタスクを会話ごとに表示",
    "直近50件のリモートタスクを会話ごとに表示"
  ],
  "noChats": [
    "最近の会話はありません",
    "最近の会話はありません"
  ],
  "newHeading": [
    "スマホで何をしますか？",
    "スマホで何をしますか？"
  ],
  "newHint": [
    "Mika JPに依頼してください。進捗、質問、回答がここに表示されます。",
    "Mika JPに依頼してください。進捗、質問、回答がここに表示されます。"
  ],
  "connectHint": [
    "スマホを接続すると依頼できます。",
    "スマホを接続すると依頼できます。"
  ],
  "loading": [
    "会話を読み込み中…",
    "会話を読み込み中…"
  ],
  "unavailableChat": [
    "この会話は最近のタスクにありません",
    "この会話は最近のタスクにありません"
  ],
  "unavailableHint": [
    "別の会話を選ぶか、新しい会話を始めてください。",
    "別の会話を選ぶか、新しい会話を始めてください。"
  ],
  "remoteTask": [
    "リモートタスク",
    "リモートタスク"
  ],
  "rounds": [
    "{n}回のやり取り",
    "{n}回のやり取り"
  ],
  "round": [
    "{n}回目",
    "{n}回目"
  ],
  "attention": [
    "回答が必要",
    "回答が必要"
  ],
  "handoff": [
    "スマホでの操作が必要",
    "スマホでの操作が必要"
  ],
  "active": [
    "実行中",
    "実行中"
  ],
  "starting": [
    "開始中",
    "開始中"
  ],
  "queued": [
    "待機中",
    "待機中"
  ],
  "running": [
    "実行中",
    "実行中"
  ],
  "paused": [
    "一時停止中",
    "一時停止中"
  ],
  "waiting_for_answer": [
    "回答を待っています",
    "回答を待っています"
  ],
  "waiting_on_phone": [
    "スマホで操作してください",
    "スマホで操作してください"
  ],
  "done": [
    "結果を受信しました",
    "結果を受信しました"
  ],
  "failed": [
    "完了できませんでした",
    "完了できませんでした"
  ],
  "cancelled": [
    "キャンセル済み",
    "キャンセル済み"
  ],
  "msgStarting": [
    "スマホが依頼を受け付けました。開始を待っています。",
    "スマホが依頼を受け付けました。開始を待っています。"
  ],
  "msgRunning": [
    "Mika JPがタスクを実行しています。",
    "Mika JPがタスクを実行しています。"
  ],
  "msgQueued": [
    "先のタスクが終わると自動で開始します。",
    "先のタスクが終わると自動で開始します。"
  ],
  "msgPaused": [
    "スマホでの再開を待っています。",
    "スマホでの再開を待っています。"
  ],
  "waitingCondition": [
    "待っている条件",
    "待っている条件"
  ],
  "msgQuestion": [
    "Mika JPが回答を待っています。",
    "Mika JPが回答を待っています。"
  ],
  "msgConfirm": [
    "スマホでこの操作を確認してください。",
    "スマホでこの操作を確認してください。"
  ],
  "msgHandoff": [
    "スマホでこの手順を操作してください。",
    "スマホでこの手順を操作してください。"
  ],
  "msgNoAnswer": [
    "スマホから回答の本文が返されませんでした。",
    "スマホから回答の本文が返されませんでした。"
  ],
  "msgNoReason": [
    "スマホから失敗の理由が返されませんでした。",
    "スマホから失敗の理由が返されませんでした。"
  ],
  "msgCancelled": [
    "このタスクを停止しました。",
    "このタスクを停止しました。"
  ],
  "handoffGuide": [
    "スマホのMika JPの通知を開いて操作してください。完了すると会話が自動で更新されます。",
    "スマホのMika JPの通知を開いて操作してください。完了すると会話が自動で更新されます。"
  ],
  "verified": [
    "確認済み",
    "確認済み"
  ],
  "verifyFailed": [
    "確認に失敗",
    "確認に失敗"
  ],
  "verifyUnknown": [
    "確認結果は未報告",
    "確認結果は未報告"
  ],
  "verification": [
    "確認の状況",
    "確認の状況"
  ],
  "expandGoal": [
    "依頼の全文を表示",
    "依頼の全文を表示"
  ],
  "collapseGoal": [
    "依頼を折りたたむ",
    "依頼を折りたたむ"
  ],
  "viewProcess": [
    "実行の様子を表示",
    "実行の様子を表示"
  ],
  "closeProcess": [
    "実行の様子を閉じる",
    "実行の様子を閉じる"
  ],
  "process": [
    "実行の様子",
    "実行の様子"
  ],
  "sidePanel": [
    "サイドパネル",
    "サイドパネル"
  ],
  "copyResult": [
    "結果をコピー",
    "結果をコピー"
  ],
  "copied": [
    "結果をコピーしました。",
    "結果をコピーしました。"
  ],
  "copyFailed": [
    "コピーできませんでした。回答の文字を選択してコピーしてください。",
    "コピーできませんでした。回答の文字を選択してコピーしてください。"
  ],
  "cancelTask": [
    "このタスクをキャンセル",
    "このタスクをキャンセル"
  ],
  "confirmCancel": [
    "このタスクのキャンセルをスマホへ依頼しますか？",
    "このタスクのキャンセルをスマホへ依頼しますか？"
  ],
  "targetTurn": [
    "このやり取りに回答",
    "このやり取りに回答"
  ],
  "message": [
    "Mika JPへのメッセージ",
    "Mika JPへのメッセージ"
  ],
  "newPlaceholder": [
    "スマホへの依頼を入力…",
    "スマホへの依頼を入力…"
  ],
  "followPlaceholder": [
    "この会話を続ける…",
    "この会話を続ける…"
  ],
  "askPlaceholder": [
    "Mika JPの質問に回答…",
    "Mika JPの質問に回答…"
  ],
  "steerPlaceholder": [
    "このタスクへの追加の依頼…",
    "このタスクへの追加の依頼…"
  ],
  "onPhonePlaceholder": [
    "スマホで操作してください…",
    "スマホで操作してください…"
  ],
  "waitingPlaceholder": [
    "スマホでの再開を待っています…",
    "スマホでの再開を待っています…"
  ],
  "noConversation": [
    "新しい依頼は「新しい会話」から始めてください。",
    "新しい依頼は「新しい会話」から始めてください。"
  ],
  "continueChat": [
    "この会話を続ける",
    "この会話を続ける"
  ],
  "reply": [
    "質問に回答",
    "質問に回答"
  ],
  "steer": [
    "依頼を追加",
    "依頼を追加"
  ],
  "submit": [
    "スマホへ依頼",
    "スマホへ依頼"
  ],
  "sendAnswer": [
    "回答を送信",
    "回答を送信"
  ],
  "sendSteer": [
    "このタスクへ追加",
    "このタスクへ追加"
  ],
  "sendContinue": [
    "この会話で続ける",
    "この会話で続ける"
  ],
  "startAt": [
    "開始時刻",
    "開始時刻"
  ],
  "phoneReport": [
    "スマホの最新の記録",
    "スマホの最新の記録"
  ],
  "observed": [
    "この画面で確認した進捗",
    "この画面で確認した進捗"
  ],
  "noSteps": [
    "スマホから過去の手順が返されませんでした。",
    "スマホから過去の手順が返されませんでした。"
  ],
  "observingNow": [
    "これから届く手順や状態の変化をここに表示します。",
    "これから届く手順や状態の変化をここに表示します。"
  ],
  "recentChanges": [
    "直近{n}回の変化を表示。",
    "直近{n}回の変化を表示。"
  ],
  "stepN": [
    "手順{n}",
    "手順{n}"
  ],
  "original": [
    "タスクの情報",
    "タスクの情報"
  ],
  "taskGoal": [
    "元の依頼",
    "元の依頼"
  ],
  "taskId": [
    "タスクID",
    "タスクID"
  ],
  "observationText": [
    "スマホからの更新",
    "スマホからの更新"
  ],
  "steerRecords": [
    "追加の依頼",
    "追加の依頼"
  ],
  "received": [
    "スマホが受信済み",
    "スマホが受信済み"
  ],
  "joined": [
    "タスクに反映済み",
    "タスクに反映済み"
  ],
  "not_applied": [
    "未反映",
    "未反映"
  ],
  "questionFromPhone": [
    "Mika JPからの質問",
    "Mika JPからの質問"
  ],
  "yourAnswer": [
    "あなたの回答",
    "あなたの回答"
  ],
  "yourSteer": [
    "あなたの追加の依頼",
    "あなたの追加の依頼"
  ],
  "cancelSent": [
    "キャンセルを依頼しました。最終的な状態はスマホからの報告を確認してください。",
    "キャンセルを依頼しました。最終的な状態はスマホからの報告を確認してください。"
  ],
  "notAccepted": [
    "スマホが操作を受け付けませんでした。現在の状態を確認してください。",
    "スマホが操作を受け付けませんでした。現在の状態を確認してください。"
  ],
  "errSession": [
    "会話デスクの接続が失効しました。現在のリンクを開き直してください。",
    "会話デスクの接続が失効しました。現在のリンクを開き直してください。"
  ],
  "errDeviceSelection": [
    "スマホを接続するか、接続中の端末から選んでください。",
    "スマホを接続するか、接続中の端末から選んでください。"
  ],
  "errDevicePinned": [
    "この会話デスクでは別のスマホを選択済みです。状態を更新してください。",
    "この会話デスクでは別のスマホを選択済みです。状態を更新してください。"
  ],
  "errAdb": [
    "選んだスマホを読み取れません。ADB接続を確認してください。",
    "選んだスマホを読み取れません。ADB接続を確認してください。"
  ],
  "errAdbUnavailable": [
    "adbを実行できません。Android platform-toolsと、--adbで指定した場所を確認してください。",
    "adbを実行できません。Android platform-toolsと、--adbで指定した場所を確認してください。"
  ],
  "errConnectionInfo": [
    "接続情報を読み取れません。スマホのロックを解除し、Mika JPを開いてから再接続してください。",
    "接続情報を読み取れません。スマホのロックを解除し、Mika JPを開いてから再接続してください。"
  ],
  "errDeviceOffline": [
    "スマホがオフラインです。接続が戻ると自動で更新します。",
    "スマホがオフラインです。接続が戻ると自動で更新します。"
  ],
  "errDeviceUnauthorized": [
    "スマホでPCを許可してください。ロックを解除してUSBデバッグを許可すると、自動で再試行します。",
    "スマホでPCを許可してください。ロックを解除してUSBデバッグを許可すると、自動で再試行します。"
  ],
  "errDeviceReconnect": [
    "スマホがオフラインです。ケーブルの接続やUSBデバッグを確認してください。自動で再試行します。",
    "スマホがオフラインです。ケーブルの接続やUSBデバッグを確認してください。自動で再試行します。"
  ],
  "errStale": [
    "スマホが再起動しました。mika connectを再実行してください。",
    "スマホが再起動しました。mika connectを再実行してください。"
  ],
  "errConnection": [
    "スマホから応答がありません。自動で再接続します。",
    "スマホから応答がありません。自動で再接続します。"
  ],
  "errDeskOffline": [
    "PC上の会話デスクに接続できません。起動状態を確認してください。",
    "PC上の会話デスクに接続できません。起動状態を確認してください。"
  ],
  "errOutcomeUnknown": [
    "操作がスマホに届いている可能性があります。再送する前に会話を確認してください。",
    "操作がスマホに届いている可能性があります。再送する前に会話を確認してください。"
  ],
  "errConflict": [
    "タスクの状態が変わりました。最新の状態を読み込みます。",
    "タスクの状態が変わりました。最新の状態を読み込みます。"
  ],
  "errFallback": [
    "タスクを更新できません。",
    "タスクを更新できません。"
  ],
  "keptList": [
    "最後に読み取れた会話を表示しています。",
    "最後に読み取れた会話を表示しています。"
  ],
  "taskGone": [
    "選択中のタスクがスマホにありません。",
    "選択中のタスクがスマホにありません。"
  ],
  "pendingRun": [
    "受信確認待ち · {id}",
    "受信確認待ち · {id}"
  ],
  "lostReceipt": [
    "スマホが受け付けた可能性があります。接続後に「元のタスクを探す」で確認してください。",
    "スマホが受け付けた可能性があります。接続後に「元のタスクを探す」で確認してください。"
  ],
  "recoveredRun": [
    "タスクIDで受信を確認しました · {id}",
    "タスクIDで受信を確認しました · {id}"
  ],
  "lookupRun": [
    "元のタスクを探す",
    "元のタスクを探す"
  ],
  "retryRun": [
    "同じIDで再試行",
    "同じIDで再試行"
  ],
  "forgetRun": [
    "受信確認を終了",
    "受信確認を終了"
  ],
  "forgetConfirm": [
    "受信確認を終了してもスマホのタスクは取り消されません。再送すると新しいタスクになります。終了しますか？",
    "受信確認を終了してもスマホのタスクは取り消されません。再送すると新しいタスクになります。終了しますか？"
  ],
  "notReceived": [
    "このIDはスマホで見つかりません。再送する場合は同じIDで再試行してください。",
    "このIDはスマホで見つかりません。再送する場合は同じIDで再試行してください。"
  ],
  "removedRun": [
    "受け付け済みの依頼ですが、その会話は後から削除されました。",
    "受け付け済みの依頼ですが、その会話は後から削除されました。"
  ]
};

const $ = id => document.getElementById(id);
// Reattach the same detail view to its turn when a refresh rebuilds the transcript.
const processContent = $("process-content");
const processSlotId = id => "turn-process-" + id;
const lang = 0;
const t = (key, vars = {}) => STRINGS[key][lang].replace(/\{(\w+)\}/g, (_, name) => vars[name]);
function applyLanguage() {
  $("language").value = "ja";
  document.documentElement.lang = "ja";
  document.title = t("title");
  for (const node of document.querySelectorAll("[data-i18n]")) node.textContent = t(node.dataset.i18n);
  for (const node of document.querySelectorAll("[data-i18n-label]"))
    node.setAttribute("aria-label", t(node.dataset.i18nLabel));
}
applyLanguage();

const tones = {
  starting: "active",
  queued: "active",
  running: "active",
  paused: "",
  waiting_for_answer: "attention",
  waiting_on_phone: "attention",
  done: "",
  failed: "bad",
  cancelled: ""
};
const terminal = new Set(["done", "failed", "cancelled"]);
const fresh = "@new";
const isActive = task => !terminal.has(task.status);
const urgency = task => (tones[task.status] === "attention" ? 0 : isActive(task) ? 1 : 2);
const threadOf = task => (task.conversation ? "chat:" + task.conversation : "task:" + task.task_id);
const date = ms =>
  new Date(ms).toLocaleString("ja-JP", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
const clock = ms =>
  new Date(ms).toLocaleTimeString("ja-JP", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const byId = (items, id) => items?.find(task => task.task_id === id);
const label = task =>
  (task?.goal || "")
    .trim()
    .split(/[\r\n。！？!?；;]/, 1)[0]
    .trim() || t("remoteTask");
function groupTasks(tasks) {
  const threads = new Map();
  // API order is newest first (including ties); show turns oldest first inside each conversation.
  for (const task of tasks) {
    const key = threadOf(task);
    if (!threads.has(key)) threads.set(key, { key, turns: [], primary: task });
    const group = threads.get(key);
    group.turns.push(task);
    if (urgency(task) < urgency(group.primary)) group.primary = task;
  }
  const ordered = [...threads.values()].sort((a, b) => urgency(a.primary) - urgency(b.primary));
  for (const thread of ordered) thread.turns.reverse();
  return ordered;
}

// The private launch link is the only source of the browser session.
const ticket = location.hash.slice(1) || sessionStorage.getItem("deskSession");
if (location.hash) {
  if (sessionStorage.getItem("deskSession") !== ticket) sessionStorage.removeItem("deskPendingRun");
  sessionStorage.setItem("deskSession", ticket);
  for (const key of ["deskGroup", "selectedTask"]) sessionStorage.removeItem(key);
  history.replaceState(null, "", location.pathname);
}
let pendingRun = JSON.parse(sessionStorage.getItem("deskPendingRun") || "null");
function setPendingRun(value) {
  pendingRun = value;
  if (value) sessionStorage.setItem("deskPendingRun", JSON.stringify(value));
  else sessionStorage.removeItem("deskPendingRun");
}
let overview = null,
  groups = [],
  groupKey = sessionStorage.getItem("deskGroup"),
  anchorId = sessionStorage.getItem("selectedTask");
let inspectId = null,
  selectedObservation = null,
  preferredTask = null,
  online = false,
  busy = false,
  observing = false;
let revision = 0,
  updateTimer,
  retryDelay = 0,
  writeWarning = "",
  autoDisclosure = false;
const drafts = new Map(),
  observed = new Map(),
  exchanges = new Map(),
  expandedGoals = new Set();
let composerKey = "",
  transcriptKey = "",
  transcriptSignature = "",
  navSignature = "",
  processSignature = "";
const group = () => groups.find(item => item.key === groupKey);
function currentTask(g = group()) {
  if (!g) return null;
  const preferred = byId(g.turns, preferredTask);
  return preferred && isActive(preferred) ? preferred : g.primary;
}

function rememberGroup(key, anchor) {
  groupKey = key;
  anchorId = anchor || null;
  if (key) sessionStorage.setItem("deskGroup", key);
  else sessionStorage.removeItem("deskGroup");
  if (anchorId) sessionStorage.setItem("selectedTask", anchorId);
  else sessionStorage.removeItem("selectedTask");
}

function chooseGroup(key) {
  const g = groups.find(i => i.key === key);
  preferredTask = null;
  inspectId = null;
  rememberGroup(key, g ? currentTask(g)?.task_id : null);
  setNav(false);
  invalidate();
  render();
  schedule();
}

function setNav(open) {
  const closing = !open && $("workspace").classList.contains("nav-open");
  $("workspace").classList.toggle("nav-open", open);
  $("open-nav").setAttribute("aria-expanded", String(open));
  if (open) ($("threads").querySelector('[aria-current="true"]') || $("new-chat")).focus();
  if (closing) $("open-nav").focus();
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

function icon(name) {
  const ns = "http://www.w3.org/2000/svg",
    n = document.createElementNS(ns, "svg"),
    use = document.createElementNS(ns, "use");
  n.setAttribute("class", "ico");
  n.setAttribute("aria-hidden", "true");
  use.setAttribute("href", "#i-" + name);
  n.append(use);
  return n;
}

function commandButton(action, name, glyph, id) {
  const b = el("button");
  b.type = "button";
  b.dataset.action = action;
  if (id) b.dataset.task = id;
  if (glyph) b.append(icon(glyph));
  b.append(el("span", "", name));
  return b;
}

function message(id, text) {
  if ($(id).textContent !== text) $(id).textContent = text;
  $(id).hidden = !text;
}

function taskMessage(task) {
  switch (task.status) {
    case "starting":
      return t("msgStarting");
    case "running":
      return task.progress || t("msgRunning");
    case "queued":
      return task.progress || t("msgQueued");
    case "paused":
      return task.progress || task.waiting_for || t("msgPaused");
    case "waiting_for_answer":
      return task.question || t("msgQuestion");
    case "waiting_on_phone":
      return (
        task.message ||
        task.progress ||
        task.reason ||
        t(task.waiting_for === "confirmation" ? "msgConfirm" : "msgHandoff")
      );
    case "done":
      return task.answer || t("msgNoAnswer");
    case "failed":
      return task.reason || task.failure_kind || t("msgNoReason");
    case "cancelled":
      return t("msgCancelled");
  }
}

function verification(task) {
  return t(task.verified === true ? "verified" : task.verified === false ? "verifyFailed" : "verifyUnknown");
}

function noteProgress(tasks) {
  const ids = new Set(tasks.map(task => task.task_id));
  for (const map of [observed, exchanges, expandedGoals])
    for (const id of map.keys()) if (!ids.has(id)) map.delete(id);
  for (const task of tasks) {
    // Finished snapshots are results, not an archive of earlier phone steps.
    if (!isActive(task)) continue;
    // Compare phone fields. Translating a fallback message does not change what the phone reported.
    const { status, step, progress, question, message, reason, waiting_for } = task;
    const item = { status, step, progress, question, message, reason, waiting_for };
    let history = observed.get(task.task_id);
    if (!history) {
      history = { rows: [], trimmed: false };
      observed.set(task.task_id, history);
    }
    const prev = history.rows.at(-1);
    if (prev && Object.keys(item).every(field => prev[field] === item[field])) continue;
    history.rows.push({ ...item, at: Date.now() });
    if (history.rows.length > 60) {
      history.rows.shift();
      history.trimmed = true;
    }
  }
}

function renderNav() {
  $("new-chat").setAttribute("aria-current", String(groupKey === fresh));
  $("count").textContent = groups.length || "";
  $("count").title = t("recentHint");
  const rows = groups.map(g => {
    const task = g.primary;
    const shortLabel = { waiting_for_answer: "attention", waiting_on_phone: "handoff" }[task.status];
    const marker = isActive(task) ? t(shortLabel || task.status) : "";
    return { key: g.key, name: label(g.turns[0]), count: g.turns.length, marker };
  });
  const signature = JSON.stringify([rows, groupKey, busy, !!overview]);
  if (signature === navSignature) return;
  navSignature = signature;
  const list = $("threads"),
    focus = document.activeElement?.dataset.group;
  list.replaceChildren();
  if (!rows.length) list.append(el("p", "nav-empty", t(overview ? "noChats" : "loading")));
  for (const row of rows) {
    const b = el("button", "conversation");
    b.type = "button";
    b.dataset.group = row.key;
    b.disabled = busy;
    b.setAttribute("aria-current", String(row.key === groupKey));
    b.title = row.name + " · " + t("rounds", { n: row.count });
    b.append(icon("chat"), el("span", "name", row.name));
    if (row.marker) b.append(el("small", "", row.marker));
    list.append(b);
    if (focus === row.key && !busy) b.focus({ preventScroll: true });
  }
}

function scrollAnchor() {
  const sc = $("scroll-thread"),
    top = sc.getBoundingClientRect().top;
  const child = Array.from($("transcript").children).find(
    n => n.dataset.id && n.getBoundingClientRect().bottom > top
  );
  return {
    end: sc.scrollHeight - sc.clientHeight - sc.scrollTop < 28,
    id: child?.dataset.id,
    offset: child ? child.getBoundingClientRect().top - top : 0
  };
}

function restoreScroll(a, reset = false) {
  const sc = $("scroll-thread");
  if (reset || a.end) {
    sc.scrollTop = sc.scrollHeight;
    return;
  }
  const n = Array.from($("transcript").children).find(item => item.dataset.id === a.id);
  if (n) sc.scrollTop += n.getBoundingClientRect().top - sc.getBoundingClientRect().top - a.offset;
}

function addTurn(task, index) {
  const id = task.task_id,
    node = el("article", "turn");
  node.dataset.id = id;
  const turn = t("round", { n: index + 1 });
  node.setAttribute("aria-label", turn);
  node.append(el("div", "turn-label", turn + (task.started_at_ms ? " · " + date(task.started_at_ms) : "")));
  const request = el("div", "request"),
    goal = task.goal || t("remoteTask"),
    chars = Array.from(goal),
    long = chars.length > 160;
  request.append(el("p", "", long && !expandedGoals.has(id) ? chars.slice(0, 150).join("") + "…" : goal));
  if (long) {
    const b = commandButton("goal", t(expandedGoals.has(id) ? "collapseGoal" : "expandGoal"), "", id);
    b.className = "expand-text";
    b.setAttribute("aria-expanded", String(expandedGoals.has(id)));
    request.append(b);
  }
  node.append(request);
  for (const item of exchanges.get(id) || []) {
    if (item.question) node.append(el("p", "guidance", item.question));
    const msg = el("div", "request extra-input");
    msg.append(el("small", "", t(item.mode === "answer" ? "yourAnswer" : "yourSteer")), el("p", "", item.text));
    node.append(msg);
  }
  const author = el("div", "author"),
    logo = el("img");
  logo.src = "/mika-logo.png";
  logo.width = 18;
  logo.height = 18;
  logo.alt = "";
  const state = el("span", "state", t(task.status));
  state.dataset.tone = tones[task.status];
  author.append(logo, el("span", "", "Mika"), state);
  if (task.status === "done") {
    const mark = el("span", "verification", "· " + verification(task));
    mark.dataset.tone = task.verified === true ? "good" : task.verified === false ? "warn" : "";
    author.append(mark);
  } else if (task.status === "running" && task.step != null)
    author.append(el("span", "state", t("stepN", { n: task.step })));
  const process = commandButton("process", t("viewProcess"), "chevron", id);
  process.className = "turn-process";
  const slot = el("div");
  slot.id = processSlotId(id);
  slot.hidden = true;
  process.setAttribute("aria-controls", slot.id);
  process.setAttribute("aria-expanded", "false");
  node.append(author, process, slot, el("p", "answer", taskMessage(task)));
  if (task.status === "paused" && task.progress && task.waiting_for && task.progress !== task.waiting_for) {
    node.append(el("p", "guidance", t("waitingCondition") + " · " + task.waiting_for));
  }
  if (task.status === "waiting_on_phone")
    node.append(el("p", "guidance", [task.app, t("handoffGuide")].filter(Boolean).join(" · ")));
  const tools = el("div", "turn-tools");
  if (task.status === "done" && task.answer) tools.append(commandButton("copy", t("copyResult"), "copy", id));
  if (isActive(task)) {
    if (
      currentTask()?.task_id !== id &&
      (task.status === "waiting_for_answer" ||
        (task.status === "running" && overview?.capabilities.includes("task_steer")))
    )
      tools.append(commandButton("target", t("targetTurn"), "reply", id));
    const stop = commandButton("cancel", t("cancelTask"), "", id);
    stop.disabled = busy || !online;
    tools.append(stop);
  }
  if (tools.childElementCount) node.append(tools);
  return node;
}

function renderTurns() {
  const g = group(),
    container = $("transcript");
  const signature = JSON.stringify([
    groupKey,
    g?.turns || [],
    !!overview,
    online,
    busy,
    preferredTask,
    [...expandedGoals],
    g?.turns.map(task => exchanges.get(task.task_id)?.length || 0)
  ]);
  if (signature === transcriptSignature) {
    renderProcess();
    return;
  }
  transcriptSignature = signature;
  const reset = transcriptKey !== groupKey;
  transcriptKey = groupKey;
  const pos = scrollAnchor(),
    focus = document.activeElement,
    ft = focus?.dataset.task,
    fa = focus?.dataset.action;
  const focusedInProcess = processContent.contains(focus);
  container.replaceChildren();
  if (g) {
    g.turns.forEach((task, index) => container.append(addTurn(task, index)));
  } else {
    const empty = el("div", "empty"),
      missing = !!groupKey && groupKey !== fresh && !!overview;
    empty.append(
      el("h2", "", t(missing ? "unavailableChat" : overview ? "newHeading" : "loading")),
      el("p", "", t(missing ? "unavailableHint" : online ? "newHint" : "connectHint"))
    );
    container.append(empty);
  }
  renderProcess();
  if (focusedInProcess && inspectId) {
    const replacement = focus.isConnected
      ? focus
      : processContent.querySelector(focus.matches("summary") ? "summary" : '.event-choice[aria-pressed="true"]');
    replacement?.focus({ preventScroll: true });
  } else if (ft && fa)
    Array.from(container.querySelectorAll("button"))
      .find(b => b.dataset.task === ft && b.dataset.action === fa)
      ?.focus({ preventScroll: true });
  restoreScroll(pos, reset);
}

function processSection(name, child) {
  const sec = el("section", "process-section");
  sec.append(el("h3", "", name), child);
  return sec;
}

function renderProcess() {
  const g = group(),
    task = byId(g?.turns, inspectId);
  if (inspectId && !task) inspectId = null;
  const open = !!task;
  const toggle = $("toggle-process");
  toggle.hidden = !g;
  const target = task || currentTask();
  if (target) toggle.setAttribute("aria-controls", processSlotId(target.task_id));
  else toggle.removeAttribute("aria-controls");
  toggle.setAttribute("aria-expanded", String(open));
  toggle.querySelector("span").textContent = t(open ? "closeProcess" : "viewProcess");
  for (const button of $("transcript").querySelectorAll('[data-action="process"]')) {
    const expanded = open && button.dataset.task === inspectId;
    const slot = $(button.getAttribute("aria-controls"));
    button.setAttribute("aria-expanded", String(expanded));
    button.querySelector("span").textContent = t(expanded ? "closeProcess" : "viewProcess");
    slot.hidden = !expanded;
    if (expanded && processContent.parentElement !== slot) slot.append(processContent);
  }
  processContent.hidden = !open;
  if (!open) {
    processSignature = "";
    return;
  }
  const hist = observed.get(inspectId),
    n = g.turns.indexOf(task) + 1;
  const rows = hist?.rows || [],
    focusedRow = rows.includes(selectedObservation) ? selectedObservation : rows.at(-1);
  const sig = JSON.stringify([task, n, rows.length, rows.at(-1)?.at, hist?.trimmed, rows.indexOf(focusedRow)]);
  if (sig === processSignature) return;
  processSignature = sig;
  const content = processContent,
    keepDetails = content.dataset.id === inspectId && content.querySelector("details")?.open;
  const focusStep =
    content.contains(document.activeElement) && document.activeElement.hasAttribute("data-observation");
  content.dataset.id = inspectId;
  content.setAttribute("aria-label", t("round", { n }) + " · " + t("process"));
  content.replaceChildren();
  const timeline = el("div");
  if (rows.length) {
    const list = el("ol", "events");
    for (const [i, row] of rows.entries()) {
      const item = el("li"),
        button = el("button", "event-choice");
      button.type = "button";
      button.dataset.observation = String(i);
      button.setAttribute("aria-pressed", String(row === focusedRow));
      const description = taskMessage(row);
      button.append(
        el("span", "event-step", row.step != null ? t("stepN", { n: row.step }) : t(row.status)),
        el("span", "event-title", description)
      );
      button.title = description;
      item.append(button);
      list.append(item);
    }
    timeline.append(list);
    if (hist.trimmed) timeline.append(el("p", "process-note", t("recentChanges", { n: hist.rows.length })));
  } else timeline.append(el("p", "process-note", t(isActive(task) ? "observingNow" : "noSteps")));
  content.append(processSection(t("observed"), timeline));
  if (focusedRow) {
    const reading = el("div", "event-reading");
    reading.append(
      el("div", "observed-at", clock(focusedRow.at) + " · " + t(focusedRow.status)),
      el("p", "event-body", taskMessage(focusedRow))
    );
    content.append(processSection(t("observationText"), reading));
  }
  if (task.status === "failed" || task.status === "cancelled")
    content.append(processSection(t("phoneReport"), el("p", "process-value", taskMessage(task))));
  if (task.status === "done")
    content.append(
      processSection(
        t("verification"),
        el(
          "p",
          "process-value",
          [verification(task), task.verification_note, ...(task.unconfirmed || [])].filter(Boolean).join("\n")
        )
      )
    );
  if (task.steers?.length)
    content.append(
      processSection(
        t("steerRecords"),
        el(
          "p",
          "process-value",
          task.steers.map((s, i) => i + 1 + ". " + (STRINGS[s.state] ? t(s.state) : s.state)).join("\n")
        )
      )
    );
  const details = el("details", "process-facts");
  details.open = !!keepDetails;
  details.append(el("summary", "", t("original")));
  const fields = el("dl");
  if (task.started_at_ms) fields.append(el("dt", "", t("startAt")), el("dd", "", date(task.started_at_ms)));
  fields.append(
    el("dt", "", t("taskGoal")),
    el("dd", "", task.goal || "—"),
    el("dt", "", t("taskId")),
    el("dd", "", task.task_id)
  );
  details.append(fields);
  content.append(details);
  if (focusStep) content.querySelector('.event-choice[aria-pressed="true"]')?.focus({ preventScroll: true });
}

function toggleProcess(id) {
  const slot = $(processSlotId(id));
  if (!slot) return;
  const trigger = slot.previousElementSibling;
  const scroller = $("scroll-thread");
  const viewport = scroller.getBoundingClientRect();
  const previousTop = trigger.getBoundingClientRect().top;
  inspectId = inspectId === id ? null : id;
  selectedObservation = null;
  setNav(false);
  renderProcess();
  // Keep the clicked line in place when closing an earlier disclosure above it.
  if (previousTop >= viewport.top && previousTop < viewport.bottom) {
    scroller.scrollTop += trigger.getBoundingClientRect().top - previousTop;
  }
  if (inspectId) {
    const triggerTop = trigger.getBoundingClientRect().top;
    if (triggerTop < viewport.top || slot.getBoundingClientRect().bottom > viewport.bottom) {
      scroller.scrollTop += triggerTop - viewport.top - 8;
    }
  }
}

function composeTarget() {
  const g = group(),
    task = currentTask(g);
  if (!g) {
    if (groupKey && groupKey !== fresh) return { kind: "wait", mode: "unavailableChat", hint: "unavailableHint" };
    return { kind: "run", key: fresh, mode: "newChat", hint: "newPlaceholder", send: "submit" };
  }
  const turn = g.turns.indexOf(task) + 1;
  if (task.status === "waiting_for_answer")
    return {
      kind: "answer",
      key: JSON.stringify(["answer", task.task_id, task.question]),
      task,
      turn,
      mode: "reply",
      hint: "askPlaceholder",
      send: "sendAnswer",
      options: task.options
    };
  if (task.status === "running" && overview?.capabilities.includes("task_steer"))
    return {
      kind: "steer",
      key: "steer:" + task.task_id,
      task,
      turn,
      mode: "steer",
      hint: "steerPlaceholder",
      send: "sendSteer"
    };
  if (isActive(task))
    return {
      kind: "wait",
      task,
      turn,
      mode: task.status,
      hint: task.status === "waiting_on_phone" ? "onPhonePlaceholder" : "waitingPlaceholder"
    };
  if (task.conversation)
    return {
      kind: "run",
      key: "continue:" + g.key,
      conversation: task.conversation,
      mode: "continueChat",
      hint: "followPlaceholder",
      send: "sendContinue"
    };
  return { kind: "wait", mode: "noConversation", hint: "noConversation" };
}
let optionsSignature = "";
function syncComposer() {
  const route = composeTarget(),
    goal = $("goal"),
    key = route.key || "";
  if (key !== composerKey) {
    goal.value = drafts.get(key) || "";
    composerKey = key;
  }
  goal.placeholder = t(route.hint);
  goal.maxLength = route.kind === "answer" ? 2000 : 4000;
  goal.readOnly = busy;
  goal.disabled = route.kind === "wait";
  $("compose-mode").textContent = (route.turn ? t("round", { n: route.turn }) + " · " : "") + t(route.mode);
  $("compose-label").textContent = t(route.send || "message");
  $("submit-goal").setAttribute("aria-label", t(route.send || "submit"));
  $("submit-goal").title = t(route.send || "submit");
  $("submit-goal").disabled = !online || busy || route.kind === "wait" || (route.kind === "run" && !!pendingRun) || !goal.value.trim();
  const opts = Array.isArray(route.options) ? route.options : [];
  const sig = JSON.stringify([key, opts, busy, online]);
  if (sig === optionsSignature) return;
  optionsSignature = sig;
  const box = $("options"),
    focused = box.contains(document.activeElement) ? document.activeElement.textContent : null;
  box.replaceChildren();
  for (const option of opts) {
    const b = el("button", "", option);
    b.type = "button";
    b.disabled = busy || !online;
    b.addEventListener("click", () => {
      goal.value = option;
      goal.dispatchEvent(new Event("input"));
      goal.focus();
    });
    box.append(b);
    if (focused === option && !b.disabled) b.focus({ preventScroll: true });
  }
}

function render() {
  const g = group();
  const title = g ? label(g.turns[0]) : t(groupKey && groupKey !== fresh ? "heading" : "newChat");
  $("thread-title").textContent = title;
  $("thread-title").title = title;
  $("thread-meta").textContent = g ? t("rounds", { n: g.turns.length }) : "";
  $("new-chat").disabled = busy;
  $("refresh").disabled = busy;
  $("pick-device").disabled = busy;
  $("pending-run").hidden = !pendingRun || busy;
  if (pendingRun) {
    message("pending-label", t("pendingRun", { id: pendingRun.fields.request_id }));
    $("lookup-run").disabled = !online;
    $("retry-run").disabled = !online || !overview?.capabilities.includes("task_recovery");
  }
  renderNav();
  renderTurns();
  syncComposer();
}

const errorNames = {
  session: "errSession",
  device_selection: "errDeviceSelection",
  device_pinned: "errDevicePinned",
  adb: "errAdb",
  adb_unavailable: "errAdbUnavailable",
  device_offline: "errDeviceOffline",
  stale_connection: "errStale",
  connection_info: "errConnectionInfo",
  connection: "errConnection",
  desk_offline: "errDeskOffline",
  outcome_unknown: "errOutcomeUnknown",
  conflict: "errConflict"
};
// device_offline carries the phone's ADB state; any other state keeps the generic text.
const deviceStates = new Map([
  ["unauthorized", "errDeviceUnauthorized"],
  ["offline", "errDeviceReconnect"]
]);
function connectionState(phase, labelKey) {
  const d = $("connection-details");
  if (d.dataset.phase !== phase) {
    if (phase !== "connected" && !d.open) {
      d.open = true;
      autoDisclosure = true;
    } else if (phase === "connected" && autoDisclosure) {
      d.open = false;
      autoDisclosure = false;
    }
    d.dataset.phase = phase;
  }
  $("connection-line").textContent = t(labelKey);
}

function updateDevice(next) {
  $("pick").hidden = true;
  $("device-label").textContent = t(next.transport === "adb" ? "adbPhone" : "networkPhone");
  $("target").textContent = next.target || "—";
  $("connection").textContent = t("connected") + (next.server_version ? " · " + next.server_version : "");
  const d = next.device || {};
  const state = t(
    d.ready === true
      ? "unlocked"
      : d.device_locked || d.keyguard_showing
        ? "locked"
        : d.interactive === false
          ? "screenOff"
          : "unknown"
  );
  $("device").textContent = [
    state,
    d.helper_available == null ? "" : t(d.helper_available ? "helperOnline" : "helperOffline")
  ]
    .filter(Boolean)
    .join(" · ");
  connectionState("connected", "online");
  $("updated").textContent = t("updated", { time: clock(Date.now()) });
}

function showProblem(e) {
  const key = (e.code === "device_offline" && deviceStates.get(e.state)) || errorNames[e.code];
  const detail = e.code === "outcome_unknown" && pendingRun && (!e.task_id || e.task_id === pendingRun.fields.request_id)
    ? t("lostReceipt") : key ? t(key) : e.message || t("errFallback");
  if (e.code === "outcome_unknown") writeWarning = detail;
  // A phone rejecting an outdated action is still a connected phone.
  if (e.http_status === 409 && e.code !== "device_selection" && e.code !== "device_pinned" && online) {
    message("notice", detail);
    return;
  }
  online = false;
  retryDelay = [401, 403, 429].includes(e.http_status) ? 60000 : Math.min(30000, retryDelay * 2 || 2000);
  const devices = e.code === "device_selection" ? e.devices || [] : [];
  const picker = $("devices");
  if (JSON.stringify(Array.from(picker.options, o => o.value)) !== JSON.stringify(devices)) {
    const prev = picker.value;
    picker.replaceChildren(...devices.map(serial => new Option(serial, serial)));
    if (devices.includes(prev)) picker.value = prev;
  }
  $("pick").hidden = !devices.length;
  $("connection").textContent = t(devices.length ? "choosing" : "offline");
  $("device").textContent = "—";
  connectionState(devices.length ? "choose" : "retry", devices.length ? "choosing" : "offline");
  message(
    "error",
    [writeWarning !== detail && writeWarning, detail, overview && t("keptList")].filter(Boolean).join(" ")
  );
}

async function call(action, fields = {}) {
  const write = !["overview", "select", "watch", "status"].includes(action);
  let response, data;
  try {
    response = await fetch("/desk", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Mika-Desk": ticket || "" },
      body: JSON.stringify({ action, ...fields })
    });
    if (response.headers.get("Content-Type")?.includes("application/json")) data = await response.json();
  } catch {
    throw { code: write ? "outcome_unknown" : "desk_offline" };
  }
  if (data?.error) throw data.error;
  if (!response.ok || !data) {
    if (response.status === 403) throw { code: "session" };
    throw { code: write && (response.ok || response.status >= 500) ? "outcome_unknown" : "desk_offline" };
  }
  return data;
}

async function refresh({ device, watchId, at = revision } = {}) {
  let next;
  if (device !== undefined) next = await call("select", { device });
  else if (watchId) next = await call("watch", { task_id: watchId });
  else next = await call("overview");
  if (at !== revision) return false;
  const keep = inspectId || anchorId;
  if (keep && !byId(next.tasks, keep) && groupKey !== fresh) {
    try {
      const task = await call("status", { task_id: keep });
      if (at !== revision) return false;
      if (!groupKey || threadOf(task) === groupKey) next.tasks.push(task);
    } catch (e) {
      if (at !== revision) return false;
      if (![404, 410].includes(e.http_status)) throw e;
      if (keep === inspectId) inspectId = null;
      if (keep === anchorId) {
        anchorId = null;
        sessionStorage.removeItem("selectedTask");
      }
      message("notice", t("taskGone"));
    }
  }
  noteProgress(next.tasks);
  groups = groupTasks(next.tasks);
  const recovered = pendingRun && byId(next.tasks, pendingRun.fields.request_id);
  if (recovered) {
    const { key, fields } = pendingRun;
    const draft = drafts.get(key);
    // Confirm from the ordinary read without sending another command or replacing an edited draft.
    const atSubmission = groupKey === (fields.conversation ? threadOf(recovered) : fresh);
    acknowledgeSubmission(recovered, key, fields.goal, atSubmission && (!draft || draft.trim() === fields.goal) && !inspectId);
    message("notice", t("recoveredRun", { id: recovered.task_id }));
  }
  if (!groupKey) {
    const legacy = byId(next.tasks, anchorId),
      chosen = legacy ? threadOf(legacy) : groups[0]?.key || fresh;
    rememberGroup(chosen, legacy?.task_id || groups.find(g => g.key === chosen)?.turns.at(-1)?.task_id);
  }
  const current = byId(group()?.turns, preferredTask) || currentTask();
  if (current && anchorId !== current.task_id) rememberGroup(groupKey, current.task_id);
  overview = next;
  online = true;
  retryDelay = 0;
  updateDevice(next);
  message("error", writeWarning);
  return true;
}

function invalidate() {
  revision++;
  clearTimeout(updateTimer);
}

function watchedTask() {
  const sel = currentTask();
  return sel && isActive(sel) ? sel : overview?.tasks.find(isActive);
}

function schedule(immediate = false) {
  clearTimeout(updateTimer);
  if (busy || observing || document.hidden) return;
  updateTimer = setTimeout(() => observe(immediate), immediate ? 0 : retryDelay || (watchedTask() ? 750 : 8000));
}

async function observe(immediate) {
  if (busy || observing || document.hidden) return;
  observing = true;
  const at = revision;
  try {
    if (await refresh({ watchId: online && !immediate ? watchedTask()?.task_id : undefined, at })) render();
  } catch (e) {
    if (at === revision) {
      showProblem(e);
      render();
    }
  } finally {
    observing = false;
    schedule(at !== revision);
  }
}

async function work(fn, write = false) {
  if (busy) return;
  busy = true;
  invalidate();
  if (write) {
    writeWarning = "";
    message("notice", "");
    message("error", "");
  }
  render();
  let refreshConflict = false;
  try {
    await fn();
  } catch (e) {
    showProblem(e);
    refreshConflict = e.http_status === 409 && online;
  } finally {
    busy = false;
    render();
    schedule(refreshConflict);
  }
}

function acknowledgeSubmission(task, key, text, select) {
  if (drafts.get(key)?.trim() === text) {
    drafts.delete(key);
    if (composerKey === key) $("goal").value = "";
  }
  setPendingRun(null);
  writeWarning = "";
  if (select) {
    preferredTask = task.task_id;
    inspectId = null;
    rememberGroup(threadOf(task), task.task_id);
  }
}

function openSubmittedTask(task, key, text) {
  acknowledgeSubmission(task, key, text, true);
  const recent = overview?.tasks || [];
  const tasks = byId(recent, task.task_id)
    ? recent.map(old => old.task_id === task.task_id ? task : old)
    : [task, ...recent];
  overview = { ...overview, tasks };
  groups = groupTasks(tasks);
  noteProgress(tasks);
  render();
}

async function recoverSubmission(retry) {
  if (!pendingRun || !online) return;
  const { key, fields } = pendingRun;
  let opened = false;
  await work(async () => {
    let task;
    try {
      task = retry ? await call("run", fields) : await call("status", { task_id: fields.request_id });
    } catch (e) {
      if (!retry && e.http_status === 404) return message("notice", t("notReceived"));
      if (e.http_status !== 410) throw e;
      setPendingRun(null);
      writeWarning = "";
      message("error", "");
      return message("notice", t("removedRun"));
    }
    openSubmittedTask(task, key, fields.goal);
    opened = true;
    message("notice", "");
    await refresh();
  });
  if (opened) $(processSlotId(fields.request_id))?.closest(".turn")?.scrollIntoView({ block: "nearest" });
}
$("lookup-run").addEventListener("click", () => recoverSubmission(false));
$("retry-run").addEventListener("click", () => recoverSubmission(true));
$("forget-run").addEventListener("click", () => {
  if (!busy && confirm(t("forgetConfirm"))) {
    setPendingRun(null);
    writeWarning = "";
    message("error", "");
    message("notice", "");
    render();
  }
});

async function submit() {
  const route = composeTarget(),
    goal = $("goal"),
    text = goal.value.trim(),
    key = route.key;
  if (!online || busy || !text || !key || route.kind === "wait" || (route.kind === "run" && pendingRun)) return;
  await work(async () => {
    if (route.kind === "run") {
      const fields = { goal: text };
      if (route.conversation) fields.conversation = route.conversation;
      if (overview.capabilities.includes("task_recovery")) {
        fields.request_id = crypto.randomUUID();
        setPendingRun({ key, fields });
      }
      let task;
      try { task = await call("run", fields); }
      catch (e) {
        if (e.code !== "outcome_unknown" && e.http_status >= 400 && e.http_status < 500) setPendingRun(null);
        throw e;
      }
      openSubmittedTask(task, key, text);
    } else {
      const id = route.task.task_id,
        receipt = await call(route.kind, { task_id: id, text });
      if (!receipt.accepted) {
        message("notice", t("notAccepted"));
      } else {
        drafts.delete(key);
        goal.value = "";
        const turns = exchanges.get(id) || [];
        turns.push({ mode: route.kind, text, question: route.kind === "answer" ? route.task.question : null });
        exchanges.set(id, turns);
      }
    }
    await refresh();
  }, true);
}
$("new-chat").addEventListener("click", () => {
  if (!busy) {
    chooseGroup(fresh);
    $("goal").focus();
  }
});
$("threads").addEventListener("click", ev => {
  const b = ev.target.closest("button[data-group]");
  if (b && !busy) chooseGroup(b.dataset.group);
});
$("open-nav").addEventListener("click", () => setNav(true));
$("close-nav").addEventListener("click", () => {
  setNav(false);
  $("open-nav").focus();
});
$("connection-details")
  .querySelector("summary")
  .addEventListener("click", () => {
    autoDisclosure = false;
  });
$("language").addEventListener("change", () => {
  const value = $("language").value;
  if (value === "auto") localStorage.removeItem("mikaDeskLanguage");
  else localStorage.setItem("mikaDeskLanguage", value);
  applyLanguage();
  navSignature = transcriptSignature = processSignature = optionsSignature = "";
  if (online) updateDevice(overview);
  else
    connectionState($("connection-details").dataset.phase || "retry", $("pick").hidden ? "offline" : "choosing");
  render();
});
$("refresh").addEventListener("click", () => work(() => refresh()));
$("pick").addEventListener("submit", ev => {
  ev.preventDefault();
  work(() => refresh({ device: $("devices").value }));
});
$("toggle-process").addEventListener("click", () => {
  const task = currentTask();
  if (task) toggleProcess(inspectId || task.task_id);
});
function collapseProcess() {
  if (!inspectId) return;
  const old = inspectId;
  const returnFocus = processContent.contains(document.activeElement);
  toggleProcess(old);
  if (returnFocus) $(processSlotId(old))?.previousElementSibling?.focus({ preventScroll: true });
}
processContent.addEventListener("click", ev => {
  const b = ev.target.closest("button[data-observation]");
  if (!b) return;
  selectedObservation = observed.get(inspectId)?.rows[Number(b.dataset.observation)] || null;
  renderProcess();
});
document.addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if ($("workspace").classList.contains("nav-open")) {
    setNav(false);
    $("open-nav").focus();
  } else if ($("connection-details").open && $("connection-details").contains(document.activeElement)) {
    $("connection-details").open = false;
    $("connection-details").querySelector("summary").focus();
  } else collapseProcess();
});
$("transcript").addEventListener("click", async ev => {
  const b = ev.target.closest("button[data-action]");
  if (!b) return;
  const task = byId(group()?.turns, b.dataset.task);
  if (!task) return;
  const id = task.task_id;
  switch (b.dataset.action) {
    case "process":
      toggleProcess(id);
      break;
    case "goal":
      if (expandedGoals.has(id)) expandedGoals.delete(id);
      else expandedGoals.add(id);
      renderTurns();
      break;
    case "copy":
      try {
        await navigator.clipboard.writeText(task.answer);
        message("notice", t("copied"));
      } catch {
        message("notice", t("copyFailed"));
      }
      break;
    case "target":
      if (!busy) {
        preferredTask = id;
        invalidate();
        render();
        schedule();
        $("goal").focus();
      }
      break;
    case "cancel":
      if (!online || busy || !isActive(task) || !confirm(t("confirmCancel"))) return;
      await work(async () => {
        const receipt = await call("cancel", { task_id: id });
        message("notice", t(receipt.accepted ? "cancelSent" : "notAccepted"));
        await refresh();
      }, true);
      break;
  }
});
$("goal").addEventListener("input", () => {
  if (composerKey) drafts.set(composerKey, $("goal").value);
  syncComposer();
});
$("goal").addEventListener("keydown", ev => {
  if (
    ev.key !== "Enter" ||
    ev.shiftKey ||
    ev.altKey ||
    ev.ctrlKey ||
    ev.metaKey ||
    ev.isComposing ||
    ev.keyCode === 229
  )
    return;
  ev.preventDefault();
  if (!ev.repeat && !$("submit-goal").disabled) $("compose").requestSubmit($("submit-goal"));
});
$("compose").addEventListener("submit", ev => {
  ev.preventDefault();
  submit();
});
document.addEventListener("visibilitychange", () => {
  invalidate();
  schedule(true);
});
render();
schedule(true);
