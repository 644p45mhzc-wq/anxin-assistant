const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const storage = {
  get(key, fallback = null) {
    try { const value = localStorage.getItem(key); return value === null ? fallback : value; }
    catch (_) { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(key, value); }
    catch (_) { /* 本地文件模式可能禁用存储，但不能影响页面交互 */ }
  }
};

function readList(key, fallback) {
  try { return JSON.parse(storage.get(key, JSON.stringify(fallback))); }
  catch (_) { return fallback; }
}

let account = null;
try { account = JSON.parse(sessionStorage.getItem('anxinSession')); } catch {}
function localMemos(key, fallback = []) {
  const rows = readList(key, fallback);
  return rows.map(item => ({...item, id: /^[A-Za-z0-9_-]{1,128}$/.test(item.id || '') ? String(item.id) : makeId()}));
}
let conversationId = null;
let historyReady = false;
const state = {
  font: storage.get('font', 'normal'),
  contrast: storage.get('contrast') === 'true',
  autoSpeak: storage.get('autoSpeak') === 'true',
  memos: account ? [] : localMemos('memos', [{text:'周五上午去社区医院复查',created:'今天 09:30'},{text:'买牛奶和降糖饼干',created:'昨天 16:20'}]),
};

function makeId() { return `${Date.now()}-${Math.random().toString(16).slice(2)}`; }


function persist() {
  storage.set('font', state.font); storage.set('contrast', state.contrast); storage.set('autoSpeak', state.autoSpeak);
}

function applySettings() {
  const sizes = { normal: '18px', large: '21px', xlarge: '24px' };
  document.documentElement.style.setProperty('--base', sizes[state.font]);
  document.body.classList.toggle('high-contrast', state.contrast);
  $('#contrastToggle').checked = state.contrast; $('#autoSpeakToggle').checked = state.autoSpeak;
  $$('.segmented button').forEach(b => b.classList.toggle('selected', b.dataset.font === state.font));
  $('#fontLevel').textContent = ({normal:'标准',large:'大',xlarge:'特大'})[state.font];
}

let chatPosition = { top: 0, anchor: null, offset: 0 };
const menuToggle = $('#menuToggle');
function setMenu(open) {
  menuToggle.setAttribute('aria-expanded', String(open));
  $('#siteNav').classList.toggle('open', open);
}
menuToggle.onclick = () => setMenu(menuToggle.getAttribute('aria-expanded') !== 'true');
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && $('#siteNav').classList.contains('open')) {
    setMenu(false);
    menuToggle.focus();
  }
});
function showPage(id) {
  if ($('#siteNav').classList.contains('open')) {
    setMenu(false);
    menuToggle.focus();
  }
  const scroller = $('#mainContent');
  const current = $('.page.active');
  if (id === 'memoPage') refreshMemos();
  if (current.id === id) return;
  if (current.id === 'chatPage') {
    const headerBottom = scroller.getBoundingClientRect().top;
    const anchor = $$('.message, #chatComposer', current).find(item => !item.hidden && item.getBoundingClientRect().bottom > headerBottom);
    chatPosition = { top: scroller.scrollTop, anchor, offset: anchor ? anchor.getBoundingClientRect().top : 0 };
    cancelRecording();
  }
  $$('[data-bar-for]').forEach(bar => { bar.hidden = bar.dataset.barFor !== id; });
  $$('.page').forEach(p => p.classList.toggle('active', p.id === id));
  $$('.nav-item').forEach(button => {
    const active = button.dataset.page === id;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  let top = 0;
  if (id === 'chatPage') {
    const { anchor, offset } = chatPosition;
    top = anchor && !anchor.hidden
      ? scroller.scrollTop + anchor.getBoundingClientRect().top - offset
      : chatPosition.top;
  }
  scroller.scrollTo({ top, behavior: 'instant' });
}

$$('.nav-item').forEach(button => button.addEventListener('click', () => showPage(button.dataset.page)));
$('#fontButton').addEventListener('click', () => { const order = ['normal','large','xlarge']; state.font = order[(order.indexOf(state.font)+1)%3]; persist(); applySettings(); });
$$('[data-font]').forEach(button => button.addEventListener('click', () => { state.font=button.dataset.font; persist(); applySettings(); }));
$('#contrastToggle').addEventListener('change', e => { state.contrast=e.target.checked; persist(); applySettings(); });
$('#autoSpeakToggle').addEventListener('change', e => { state.autoSpeak=e.target.checked; persist(); });

function answerText(summary, detail) {
  return [summary, detail].filter(Boolean).join(' ').trim();
}

const speechState={text:'',index:0,rate:.9,paused:false,playing:false,runId:0};
function updateSpeechPlayer() {
  const total=Math.max(1,speechState.text.length),percent=Math.min(100,Math.round(speechState.index/total*100));
  $('#speechProgress').max=total; $('#speechProgress').value=Math.min(speechState.index,total); $('#speechPercent').textContent=`${percent}%`;
  $('#pauseSpeechButton').textContent=speechState.paused ? '继续' : speechState.playing ? '暂停' : '重新朗读';
  $('#speechStatus').textContent = speechState.paused ? '朗读已暂停' : speechState.playing ? '正在朗读回答' : speechState.index === total ? '朗读完毕' : '朗读已中断';
  $$('[data-rate]').forEach(b=>b.classList.toggle('selected',Number(b.dataset.rate)===speechState.rate));
}
function speakFrom(index) {
  if(!speechState.text) return;
  const thisRun = ++speechState.runId;
  speechSynthesis.cancel();
  if (speechSynthesis.paused) speechSynthesis.resume();
  speechState.index=Math.max(0,Math.min(index,speechState.text.length-1)); speechState.paused=false; speechState.playing=true;
  const start=speechState.index,utterance=new SpeechSynthesisUtterance(speechState.text.slice(start));
  utterance.lang='zh-CN'; utterance.rate=speechState.rate;
  utterance.onboundary=e=>{if(thisRun!==speechState.runId)return;speechState.index=start+e.charIndex;updateSpeechPlayer();};
  utterance.onend=()=>{if(thisRun!==speechState.runId)return;speechState.index=speechState.text.length;speechState.playing=false;speechState.paused=false;updateSpeechPlayer();};
  utterance.onerror=()=>{if(thisRun===speechState.runId){speechState.playing=false;speechState.paused=false;updateSpeechPlayer();}};
  speechSynthesis.speak(utterance); updateSpeechPlayer();
}
function startSpeech(text) {
  if(!('speechSynthesis' in window)) return alert('当前浏览器不支持朗读，请换用最新版 Chrome。');
  speechState.text=text.trim(); speechState.index=0; $('#speechPlayer').hidden=false; speakFrom(0);
}
function stopSpeech() { speechState.runId+=1; speechSynthesis.cancel(); speechState.text=''; speechState.index=0; speechState.paused=false; speechState.playing=false; $('#speechPlayer').hidden=true; }
$('#pauseSpeechButton').addEventListener('click',()=>{
  if(!speechState.text)return;
  if(speechState.paused && !speechState.playing){speakFrom(speechState.index);}
  else if(speechState.paused){speechSynthesis.resume();speechState.paused=false;speechState.playing=true;}
  else if(speechState.playing){speechSynthesis.pause();speechState.paused=true;}
  else speakFrom(0);
  updateSpeechPlayer();
});
$('#closeSpeechPlayer').addEventListener('click',stopSpeech);
$('#speechProgress').addEventListener('input',e=>{speechState.index=Number(e.target.value);updateSpeechPlayer();});
$('#speechProgress').addEventListener('change',e=>{const position=Number(e.target.value);if(position>=speechState.text.length){speechState.index=speechState.text.length;speechState.runId++;speechState.paused=false;speechSynthesis.cancel();speechState.playing=false;updateSpeechPlayer();}else speakFrom(position);});
$$('[data-rate]').forEach(button=>button.addEventListener('click',()=>{speechState.rate=Number(button.dataset.rate);if(speechState.playing && !speechState.paused)speakFrom(speechState.index);else if(speechState.paused){speechState.runId++;speechSynthesis.cancel();speechState.playing=false;updateSpeechPlayer();}else updateSpeechPlayer();}));

function addMessage(role, summary, detail='', speakText='') {
  const article=document.createElement('article'); article.className=`message ${role}`;
  if(role==='user') article.innerHTML=`<p class="message-label">您</p><div class="bubble"><p>${escapeHtml(summary)}</p></div>`;
  else {
    const fullText = answerText(summary, detail);
    const collapsible = Boolean(detail) && fullText.length > 56;
    const detailId = 'detail-' + makeId();
    article.innerHTML = `<p class="message-label">安心助手</p>
      <div class="bubble answer-card">
        <p class="answer-summary">${escapeHtml(summary)}</p>
        ${detail ? `<p id="${detailId}" class="answer-detail" ${collapsible ? 'hidden' : ''}>${escapeHtml(detail)}</p>` : ''}
        ${collapsible ? `<button class="expand-button" aria-controls="${detailId}" aria-expanded="false">展开全文</button>` : ''}
      </div>
      <button class="speak-button" type="button">听完整回答</button>`;
    const toggle = article.querySelector('.expand-button');
    if (toggle) toggle.onclick = () => {
      const content = article.querySelector('.answer-detail');
      content.hidden = !content.hidden;
      toggle.setAttribute('aria-expanded', String(!content.hidden));
      toggle.textContent = content.hidden ? '展开全文' : '收起全文';
    };
    article.querySelector('.speak-button').onclick = () => startSpeech(speakText || fullText);
  }
  $('#messages').append(article); if ($('#chatPage').classList.contains('active') && document.activeElement !== input) article.scrollIntoView({behavior:'smooth',block:'center'});
}

function escapeHtml(value) { const d=document.createElement('div'); d.textContent=value; return d.innerHTML; }
function suggestMemo(text) {
  const command = /^(?:请)?(?:帮我|给我|替我)?(?:记一下|记下来|记下|记住|记一条备忘|新增备忘|添加备忘|备忘录?|提醒我|提醒一下)[，,：:\s]*/;
  const explicit = command.test(text);
  const content = text.replace(command, '').trim();
  const timePattern = /(?:每天|每日|每晚|每早|每周[一二三四五六日天]?|今天|明天|后天|(?:下周|周|星期)[一二三四五六日天]|下周|今晚|明早|早上|上午|中午|下午|晚上|凌晨|[零〇一二两三四五六七八九十\d]+(?:点|时)(?:半|一刻|三刻|[零一二三四五六七八九十\d]+分?)?|\d{1,2}[:：]\d{2})+/;
  const when = content.match(timePattern)?.[0] || '';
  if (!explicit) {
    if (/[？?]|吗|怎么|为什么|是不是|要不要|能不能|几点|不要提醒|不用提醒|不需要提醒|不用记|不要记|不用去|不去|取消/.test(text)) return null;
    if (!/提醒我/.test(content) && !(when && /吃药|服药|要去|要带|记得|开会|复查|买|预约|接孩子|取快递|去.{1,20}/.test(content))) return null;
  }
  const task = content.replace(when, '').replace(/^[，,\s]*(?:请)?(?:提醒我)?(?:我)?(?:都)?(?:需要|要)?/, '').trim();
  return {text:task || content, when, details:''};
}

async function api(path, body, method = body === undefined ? 'GET' : 'POST') {
  const session = account;
  const headers = {};
  if (account) headers.Authorization = 'Bearer ' + account.accessToken;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch('/api/' + path, {
    method, cache:'no-store', headers, body: JSON.stringify(body)
  });
  if (response.status === 204) return;
  const data = await response.json().catch(error => {
    if (response.ok) throw error;
    return {};
  });
  if (!response.ok) {
    if (response.status === 401 && session && account === session) changeAccount(null);
    throw Object.assign(new Error(data.message || '请求失败，请稍后重试。'), {status:response.status});
  }
  return data;
}

function changeAccount(next) {
  pendingSend = null;
  cancelRecording();
  if (!$('#speechPlayer').hidden) stopSpeech();
  $('#threadTitle').textContent = '新对话';
  account = next;
  if (next) sessionStorage.setItem('anxinSession', JSON.stringify(next));
  else sessionStorage.removeItem('anxinSession');
  conversationId = null;
  historyReady = false;
  state.memos = next ? [] : localMemos('memos');
  resetMemoSync();
  $('#messages').replaceChildren();
  $('#memoSearch').value = '';
  $('#accountButton').textContent = next ? '退出登录' : '登录';
  $('#continueChat').hidden = true;
  $('#chatComposer').hidden = false;
  $('#collapseChat').hidden = true;
  updateComposer(input.value ? 'type' : 'idle');
  renderLists();
}

function rememberThread(id, title) {
  conversationId = id;
  $('#threadTitle').textContent = title || '新对话';
  if (account) sessionStorage.setItem('thread:' + account.user.id, JSON.stringify({id, title}));
}

async function restoreHistory() {
  const saved = JSON.parse(sessionStorage.getItem('thread:' + account.user.id) || 'null');
  const thread = saved || (await api('conversations?size=1')).items[0] || {};
  await loadThread(thread);
}

async function loadThread(thread, switching = false) {
  const rows = [];
  if (thread.id) {
    let page = 0;
    let result;
    do {
      result = await api('conversations/' + thread.id + '/messages?size=100&page=' + page++);
      rows.push(...result.items);
    } while (page < result.totalPages);
  }
  if (switching) resetThreadView();
  rememberThread(thread.id || null, thread.title);
  $('#messages').replaceChildren();
  for (const row of rows) addMessage(row.role, row.content, row.followUp || '', row.speakText);
  historyReady = true;
  if (rows.length && !input.value) collapseComposer();
}

function resetThreadView() {
  pendingSend = null;
  cancelRecording();
  if (!$('#speechPlayer').hidden) stopSpeech();
  input.value = '';
  $('#messages').replaceChildren();
  $('#continueChat').hidden = true;
  $('#chatComposer').hidden = false;
  $('#collapseChat').hidden = true;
  updateComposer('idle');
  $('#mainContent').scrollTop = 0;
}

function canLeaveDraft() {
  return (!input.value.trim() && !recognition) || confirm('离开后将清空未发送的输入，继续吗？');
}
$('#newChat').onclick = () => {
  if (waiting || !canLeaveDraft()) return;
  showPage('chatPage');
  resetThreadView();
  rememberThread(null, '新对话');
  historyReady = true;
};
$('#renameChat').onclick = () => {
  if (waiting) return;
  if (!account) return openLogin();
  $('#renameStatus').textContent = '';
  $('#renameInput').value = $('#threadTitle').textContent;
  $('#renameDialog').showModal();
  $('#renameInput').select();
};
$('#cancelRename').onclick = () => $('#renameDialog').close();
$('#renameForm').onsubmit = async event => {
  event.preventDefault();
  const title = $('#renameInput').value.trim();
  if (!title) { $('#renameStatus').textContent = '请输入名称。'; return; }
  if (waiting) return;
  waiting = true;
  try {
    const path = conversationId ? 'conversations/' + conversationId + '/title' : 'conversations';
    const updated = await api(path, {title});
    rememberThread(updated.id, updated.title);
    historyReady = true;
    $('#renameDialog').close();
  } catch (error) { $('#renameStatus').textContent = error.message; }
  finally { waiting = false; }
};
$('#closeHistory').onclick = () => $('#historyDialog').close();
$('#chatHistory').onclick = async () => {
  if (waiting) return;
  showPage('chatPage');
  if (!account) return openLogin();
  const dialog = $('#historyDialog');
  const list = $('#threadList');
  const notice = $('#historyNotice');
  list.replaceChildren();
  notice.textContent = '正在读取…';
  dialog.showModal();
  waiting = true;
  try {
    let page = 0;
    let result;
    do {
      result = await api('conversations?size=100&page=' + page++);
      for (const thread of result.items) {
        const button = document.createElement('button');
        button.className = 'secondary-button';
        button.textContent = thread.title + ' · ' + new Date(thread.updatedAt).toLocaleDateString('zh-CN');
        button.onclick = async () => {
          if (waiting || !canLeaveDraft()) return;
          waiting = true;
          try { await loadThread(thread, true); dialog.close(); }
          catch (error) { notice.textContent = error.message; }
          finally { waiting = false; }
        };
        list.append(button);
      }
    } while (page < result.totalPages);
    notice.textContent = list.childElementCount ? '' : '还没有历史对话。';
  } catch (error) { notice.textContent = error.message; }
  finally { waiting = false; }
};

let registering = false;
function openLogin() {
  if (location.protocol === 'file:') {
    alert('请通过后端服务的网址打开网站，再登录聊天。');
    return;
  }
  $('#authStatus').textContent = '';
  $('#authDialog').showModal();
}
$('#authSwitch').onclick = () => {
  registering = !registering;
  $('#authTitle').textContent = registering ? '注册安心助手' : '登录安心助手';
  $('#authSubmit').textContent = registering ? '注册并登录' : '登录';
  $('#authSwitch').textContent = registering ? '已有账号，去登录' : '注册新账号';
  $('#password').autocomplete = registering ? 'new-password' : 'current-password';
  $('#authStatus').textContent = '';
};
$('#authCancel').onclick = () => $('#authDialog').close();
$('#authDialog').onclose = () => { $('#password').value = ''; };
$('#accountButton').onclick = async () => {
  if (!account) return openLogin();
  if (waiting || !confirm('要退出当前账号吗？')) return;
  try {
    await api('auth/logout', {});
    input.value = '';
    changeAccount(null);
  } catch (error) { alert(error.message); }
};
$('#authForm').onsubmit = async event => {
  event.preventDefault();
  if (waiting) return;
  waiting = true;
  $('#authSubmit').disabled = true;
  const credentials = {username:$('#username').value.trim(), password:$('#password').value};
  try {
    if (registering) await api('auth/register', credentials);
    changeAccount(await api('auth/login', credentials));
    $('#authDialog').close();
    await restoreHistory();
  } catch (error) {
    if ($('#authDialog').open) $('#authStatus').textContent = error.message;
    else $('#speechHint').textContent = error.message;
  } finally {
    waiting = false;
    $('#authSubmit').disabled = false;
    $('#sendButton').disabled = !input.value.trim();
  }
};

const input = $('#messageInput');
let mode = 'idle';
let recognition = null;
let returnMode = 'idle';
let waiting = false;
let pendingSend = null;

function updateComposer(next, hint) {
  mode = next;
  $('#chatComposer').dataset.mode = next;
  const sendFailed = next === 'sendFailed';
  const editing = next === 'review' || next === 'type' || sendFailed;
  const continuing = $('#messages').childElementCount > 0;
  $('#chatTitle').textContent = {sendFailed:'还没收到回复',retry:'再试一次？',idle:'您好，想聊点什么？',listen:'正在听您说话…',review:'看看这句话，对吗？',type:continuing ? '继续对话' : '您想说什么？'}[next];
  $('#speechHint').textContent = hint || {idle:'点“开始说话”，说完可以核对文字。',listen:'说完后，点“说好了”核对文字。',review:'点文字可以修改，确认后再发送。',type:'写好后，点下面的按钮发送。'}[next];
  $('#liveTranscript').hidden = next !== 'listen';
  $('#draftArea').hidden = !editing;
  $('#sendButton').hidden = !editing;
  $('#sendButton').textContent = sendFailed ? '再试一次' : '确认发送';
  $('#editFailedMessage').hidden = !sendFailed;
  $('#micButton').hidden = sendFailed;
  $('#sendButton').disabled = waiting || !input.value.trim();
  $('#typeButton').hidden = next !== 'idle' && next !== 'retry';
  $('#typeLabel').textContent = next === 'retry' ? '改用打字' : '打字提问';
  $('#cancelSpeech').hidden = next !== 'listen';
  $('#micLabel').textContent = next === 'retry' ? '再说一次' : editing ? '继续说' : next === 'listen' ? '说好了' : '开始说话';
  $('#micButton').className = editing ? 'secondary-button' : 'primary-button';
}
input.addEventListener('input', () => {
  if (mode === 'sendFailed') updateComposer('type');
  $('#sendButton').disabled = waiting || !input.value.trim();
});
$('#editFailedMessage').onclick = () => { updateComposer('type'); input.focus(); };
$('#typeButton').onclick = () => { updateComposer('type'); input.focus(); };
function collapseComposer() {
  cancelRecording();
  $('#chatComposer').hidden = true;
  $('#continueChat').hidden = false;
  $('#continueChat').setAttribute('aria-expanded', 'false');
  $('#continueChat').focus({preventScroll:true});
}
$('#collapseChat').onclick = collapseComposer;
$('#continueChat').onclick = () => {
  $('#collapseChat').hidden = false;
  $('#chatComposer').hidden = false;
  $('#continueChat').setAttribute('aria-expanded', 'true');
  $('#continueChat').hidden = true;
  updateComposer('type');
  input.focus();
};
$('#sendButton').onclick = async () => {
  const text = input.value.trim();
  if (!text || waiting) return;
  if (!account) return openLogin();
  const session = account;
  const source = mode === 'review' ? 'speech' : 'text';
  waiting = true;
  input.readOnly = true;
  $('#micButton').disabled = true;
  $('#editFailedMessage').hidden = true;
  $('#sendButton').disabled = true;
  $('#sendButton').textContent = '请稍候';
  $('#collapseChat').disabled = true;
  $('#speechHint').textContent = '正在发送，请稍候…';
  const slowNotice = setTimeout(() => {
    if (session === account) $('#speechHint').textContent = '回复需要一点时间，请稍等。';
  }, 8000);
  const proposal = suggestMemo(text);
  let result;
  try {
    if (!historyReady) await restoreHistory();
    if (!conversationId) {
      const thread = await api('conversations', {});
      rememberThread(thread.id, thread.title);
    }
    if (!pendingSend || pendingSend.thread !== conversationId || pendingSend.text !== text) {
      pendingSend = {thread:conversationId, text, source, id:makeId()};
    }
    const endpoint = proposal ? 'memo-suggestions' : 'messages';
    $('#speechHint').textContent = '正在等安心助手回复…';
    result = await api('conversations/' + conversationId + '/' + endpoint, {
      content:text, inputSource:pendingSend.source, clientMessageId:pendingSend.id
    });
  } catch (error) {
    const reason = error.status ? error.message : '暂时没有收到服务器的回复，请检查网络后再试。';
    updateComposer('sendFailed', reason + ' 您的文字已保留。');
    return;
  } finally {
    clearTimeout(slowNotice);
    waiting = false;
    input.readOnly = false;
    $('#micButton').disabled = false;
    $('#collapseChat').disabled = false;
    $('#sendButton').disabled = !input.value.trim();
  }
  if (session !== account) return;
  pendingSend = null;
  addMessage('user', text);
  if ($('#threadTitle').textContent === '新对话') rememberThread(conversationId, Array.from(text).slice(0,40).join(''));
  addMessage('assistant', result.reply, result.followUp || '', result.speakText);
  input.value = '';
  collapseComposer();
  if (proposal) openDialog(null, proposal, $('#messages').lastElementChild);
  if (state.autoSpeak) startSpeech(result.speakText || answerText(result.reply, result.followUp));
};

function cancelRecording() {
  if (!recognition) return;
  const session = recognition;
  recognition = null;
  session.abort();
  updateComposer(returnMode);
}
$('#cancelSpeech').onclick = cancelRecording;
$('#micButton').onclick = () => {
  if (recognition) { recognition.stop(); return; }
  const Engine = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Engine) {
    updateComposer('type', '当前浏览器无法识别语音，您可以在下方打字。');
    input.focus();
    return;
  }
  returnMode = mode;
  const session = new Engine();
  recognition = session;
  let words = '';
  session.lang = 'zh-CN';
  session.interimResults = true;
  session.onresult = event => {
    if (recognition !== session) return;
    words = Array.from(event.results, item => item[0].transcript).join('').trim();
    $('#liveText').textContent = input.value + words || '等待识别文字…';
  };
  function failed(message) {
    if (recognition !== session) return;
    recognition = null;
    updateComposer('retry', message);
  }
  session.onerror = event => failed(event.error === 'not-allowed' ? '未获得麦克风权限，您可以打字，或允许使用麦克风后再试。' : '这次没有识别成功，您可以再试一次或打字。');
  session.onend = () => {
    if (recognition !== session) return;
    recognition = null;
    if (words) input.value += words;
    updateComposer(words ? 'review' : 'retry', words ? '' : '没有听清，请再试一次或打字。');
  };
  $('#liveText').textContent = input.value || '等待识别文字…';
  updateComposer('listen');
  try { session.start(); } catch { failed('麦克风暂时无法使用，您可以在下方打字。'); }
};
updateComposer('idle');

function clockNumber(value) {
  if (!value) return 0;
  if (/^\d+$/.test(value)) return Number(value);
  const digits = '零一二三四五六七八九';
  value = value.replace(/两/g,'二').replace(/〇/g,'零');
  if (value.includes('十')) {
    const [tens, ones] = value.split('十');
    return (tens ? digits.indexOf(tens) : 1) * 10 + (ones ? digits.indexOf(ones) : 0);
  }
  return Number([...value].map(c => digits.indexOf(c)).join(''));
}

function nextMemoTime(memo, now) {
  const value = memo.when || memo.text || '';
  const clock = value.match(/([零〇一二两三四五六七八九十\d]+)(?:[:：](\d{2})|[点时](半|一刻|三刻|[零〇一二三四五六七八九十\d]+分?)?)/);
  const week = value.match(/(每|下|本|这)?(?:周|星期)([一二三四五六日天])/);
  const relative = value.match(/今天|今日|今晚|明天|明早|后天/);
  const date = value.match(/(?:(\d{4})[-年/])?(\d{1,2})[-月/](\d{1,2})(?:日|号)?/);
  const daily = /每[天日早晚]/.test(value);
  if (!(clock || week || relative || date || daily)) return null;
  const anchor = new Date(memo.scheduleAnchor || memo.created);
  const base = Number.isNaN(anchor.getTime()) ? now : anchor;
  const day = new Date(base);
  let repeat = 0;
  if (date) {
    day.setFullYear(Number(date[1] || base.getFullYear()), Number(date[2])-1, Number(date[3]));
    if (day.getMonth() !== Number(date[2])-1 || day.getDate() !== Number(date[3])) return null;
  } else if (week) {
    if (week[1] === '每') { day.setTime(now.getTime()); repeat=7; }
    const target = ('一二三四五六日'.indexOf(week[2].replace('天','日')) + 1);
    const delta = target - (day.getDay() || 7);
    day.setDate(day.getDate() + (week[1] === '下' ? delta+7 : /^(本|这)$/.test(week[1]) ? delta : (delta+7)%7));
  } else if (relative) {
    day.setDate(day.getDate() + (/明/.test(relative[0]) ? 1 : relative[0]==='后天' ? 2 : 0));
  } else if (daily) {
    day.setTime(now.getTime()); repeat=1;
  }
  let hour = clockNumber(clock?.[1]);
  const minutes = clock?.[2] || clock?.[3] || '';
  const minute = ({半:30,一刻:15,三刻:45})[minutes] ?? clockNumber(minutes.replace('分',''));
  if (hour>23 || minute>59) return null;
  if (hour<12 && /下午|傍晚|晚上|今晚|每晚/.test(value)) hour+=12;
  if (hour>0 && hour<11 && /中午/.test(value)) hour+=12;
  if (hour===12 && /凌晨/.test(value)) hour=0;
  day.setHours(hour,minute,0,0);
  const threshold = new Date(now);
  if (!clock) threshold.setHours(0,0,0,0);
  else threshold.setSeconds(0,0);
  if (day < threshold && repeat) day.setDate(day.getDate()+repeat);
  if (day < threshold) return null;
  return {day, hasClock:Boolean(clock)};
}

function renderUpcomingMemo() {
  const now = new Date();
  let first;
  for (const memo of state.memos) {
    const occurrence = nextMemoTime(memo,now);
    if (occurrence && (!first || occurrence.day < first.day)) first = {...occurrence,memo};
  }
  const banner = $('#upcomingMemo');
  let when = '近期暂无提醒';
  let task = '';
  if (first) {
    const {day, hasClock, memo} = first;
    when = day.toDateString() === now.toDateString() ? '今天'
      : day.toLocaleDateString('zh-CN', {month:'long', day:'numeric', weekday:'short'});
    if (hasClock) {
      const time = day.toLocaleTimeString('zh-CN', {hour:'numeric', minute:'2-digit', hourCycle:'h12'});
      when += ' ' + time;
    }
    task = memo.text;
  }
  const dateLine = $('#reminderWhen');
  const taskLine = $('#reminderTask');
  if (dateLine.textContent !== when) dateLine.textContent = when;
  if (taskLine.textContent !== task) taskLine.textContent = task;
  taskLine.hidden = !task;
  banner.title = task ? when + ' · ' + task : when;
}
setInterval(renderUpcomingMemo, 30000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) { renderUpcomingMemo(); refreshMemos(); }
});
window.addEventListener('focus', () => refreshMemos());
window.addEventListener('online', () => refreshMemos());

let memoSequence = 0;
let loadingMemos = null;

function syncStatus(message = '') {
  $('#memoSyncStatus').textContent = message;
  $('#memoSyncStatus').hidden = !message;
}
function updateGuestImport() {
  const count = account ? readList('memos', []).length : 0;
  const button = $('#importGuest');
  button.hidden = !count;
  button.textContent = '导入本机未登录时的备忘（' + count + ' 条）';
}
function resetMemoSync() {
  memoSequence++;
  loadingMemos = null;
  syncStatus();
  updateGuestImport();
  if (account) refreshMemos();
}
function isoTime(value, fallback) {
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? fallback : time.toISOString();
}
async function migrateMemos(key, session) {
  const raw = localStorage.getItem(key);
  if (!raw) return;
  const rows = JSON.parse(raw);
  // Write missing IDs before the first request so an interrupted import can be replayed.
  for (const item of rows) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(item.id || '')) item.id = makeId();
  }
  localStorage.setItem(key, JSON.stringify(rows));
  for (const item of rows) {
    if (account !== session) return;
    const created = isoTime(item.created, new Date().toISOString());
    const fields = {text:item.text, when:item.when || '', details:item.details || '',
      scheduleAnchor:isoTime(item.scheduleAnchor || item.created, created)};
    try {
      await api('memos', {id:item.id, created, fields});
    } catch (error) {
      if (error.status !== 410) throw error;
    }
    // Re-read the key to preserve other entries added during this request.
    const remaining = JSON.parse(localStorage.getItem(key) || '[]').filter(row => row.id !== item.id);
    if (remaining.length) localStorage.setItem(key, JSON.stringify(remaining));
    else localStorage.removeItem(key);
  }
}
function refreshMemos() {
  if (!account || loadingMemos) return loadingMemos;
  const session = account;
  const seq = ++memoSequence;
  loadingMemos = (async () => {
    try {
      let migrationNotice = '';
      try { await migrateMemos('memos:' + session.user.id, session); }
      catch { migrationNotice = '部分旧备忘尚未导入，原记录仍在本机。重新打开备忘页可重试。'; }
      if (account !== session) return;
      const rows = await api('memos');
      if (seq !== memoSequence) return;
      state.memos = rows;
      renderLists();
      syncStatus(migrationNotice);
    } catch (error) {
      if (seq === memoSequence) syncStatus('备忘同步未完成。请检查网络，重新打开备忘页再试。');
    } finally {
      if (seq === memoSequence) loadingMemos = null;
    }
  })();
  return loadingMemos;
}
function applyMemoChange(rows) {
  // Ignore reads started before this write was acknowledged.
  memoSequence++;
  loadingMemos = null;
  state.memos = rows;
  renderLists();
  if (account) refreshMemos();
}
$('#importGuest').onclick = async () => {
  const session = account;
  if (!session || !confirm('把本机未登录时的备忘移入账号“' + session.user.username + '”吗？')) return;
  const button = $('#importGuest');
  button.disabled = true;
  try {
    await migrateMemos('memos', session);
    if (session === account) { await loadingMemos; await refreshMemos(); }
  } catch {
    if (session === account) syncStatus('导入未完成，请再点一次导入。');
  } finally {
    updateGuestImport();
    button.disabled = false;
  }
};

function renderLists() {
  renderUpcomingMemo();
  const query=$('#memoSearch').value.trim().toLowerCase();
  const memos=state.memos.filter(m=>[m.text,m.when,m.details].filter(Boolean).join(' ').toLowerCase().includes(query));
  $('#memoList').innerHTML=memos.length?memos.map(m=>`<article class="list-card"><div class="card-top"><div><p>${escapeHtml(m.text)}</p>${m.when ? `<p>时间：${escapeHtml(m.when)}</p>` : ''}${m.details ? `<p>细节：${escapeHtml(m.details)}</p>` : ''}<small>保存于 ${escapeHtml(m.revision ? new Date(m.created).toLocaleString('zh-CN') : m.created)}</small></div><span class="status-badge">备忘</span></div><div class="card-actions"><button class="small-action" data-action="edit-memo" data-id="${m.id}">编辑</button><button class="small-action danger" data-action="delete-memo" data-id="${m.id}">删除</button></div></article>`).join(''):'<p class="empty-state">没有找到相关备忘</p>';
}
$('#memoSearch').addEventListener('input',renderLists);

let originalMemo;
let draftId;
let draftOwner;
let savingMemo = false;
let suggestionArticle = null;
function openDialog(item = null, proposal = null, article = null) {
  const value = item || proposal || {};
  originalMemo = item;
  draftId = item?.id || makeId();
  draftOwner = account?.user.id;
  suggestionArticle = article;
  $('#dialogTitle').textContent = proposal ? '是否记入备忘？' : item ? '编辑备忘' : '新增备忘';
  $('#confirmDialog').textContent = proposal ? '是，记入备忘' : '保存';
  $('#cancelDialogButton').textContent = proposal ? '否，暂不记录' : '取消';
  $('#dialogContent').value = value.text || '';
  $('#memoWhen').value = value.when || '';
  $('#memoDetails').value = value.details || '';
  $('#memoNote').textContent = account ? '保存后，在其他设备登录同一账号也能查看。' : '这条备忘将保存在当前浏览器。';
  $('#formDialog').showModal();
}
$('#addMemoButton').onclick = () => openDialog();
function closeDialog(saved = false) {
  if (savingMemo) return;
  if (suggestionArticle) {
    const status = document.createElement('p');
    status.textContent = saved === true ? '已存入备忘' : '这次没有记录';
    suggestionArticle.append(status);
    suggestionArticle = null;
  }
  $('#dialogForm').reset();
  originalMemo = null;
  $('#formDialog').close();
}
$('#closeDialogButton').onclick = () => closeDialog();
$('#cancelDialogButton').onclick = () => closeDialog();
$('#formDialog').oncancel = event => { event.preventDefault(); closeDialog(); };
$('#dialogForm').onsubmit = async event => {
  event.preventDefault();
  if (!$('#formDialog').open || savingMemo) return;
  if (account?.user.id !== draftOwner) {
    $('#memoNote').textContent = '请先登录原账号，再保存这份草稿。';
    openLogin();
    return;
  }
  const fields = {text:$('#dialogContent').value.trim(), when:$('#memoWhen').value.trim(), details:$('#memoDetails').value.trim()};
  if (!fields.text) return $('#dialogContent').focus();
  const now = new Date().toISOString();
  const previousTime = originalMemo && (originalMemo.when || originalMemo.text);
  fields.scheduleAnchor = previousTime === (fields.when || fields.text)
    ? isoTime(originalMemo.scheduleAnchor || originalMemo.created, now) : now;
  const session = account;
  savingMemo = true;
  $('#confirmDialog').disabled = true;
  try {
    let saved;
    if (!session) {
      saved = {...fields, id:draftId, created:originalMemo?.created || new Date().toLocaleString('zh-CN')};
    } else if (originalMemo) {
      saved = await api('memos/' + draftId, {revision:originalMemo.revision, fields}, 'PUT');
    } else {
      saved = await api('memos', {id:draftId, created:now, fields});
    }
    if (account !== session) return;
    const rows = originalMemo ? state.memos.map(m => m.id === draftId ? saved : m)
      : [saved, ...state.memos.filter(m => m.id !== draftId)];
    if (!session) localStorage.setItem('memos', JSON.stringify(rows));
    applyMemoChange(rows);
    savingMemo = false;
    closeDialog(true);
  } catch (error) {
    $('#memoNote').textContent = (error.status ? error.message : '保存失败，请检查网络后再试。') + ' 您填写的内容已保留。';
    if (error.status === 409 || error.status === 404) refreshMemos();
  } finally {
    savingMemo = false;
    $('#confirmDialog').disabled = false;
  }
};

async function handleListAction(event) {
  const button = event.target.closest('[data-action]');
  if (!button) return;
  const memo = state.memos.find(m => m.id === button.dataset.id);
  if (!memo) return;
  if (button.dataset.action === 'edit-memo') return openDialog(memo);
  if (!confirm('确定删除这条备忘吗？删除后无法恢复。')) return;
  const session = account;
  button.disabled = true;
  try {
    if (session) await api('memos/' + memo.id + '?revision=' + memo.revision, undefined, 'DELETE');
    if (account !== session) return;
    const remaining = state.memos.filter(m => m.id !== memo.id);
    if (!session) localStorage.setItem('memos', JSON.stringify(remaining));
    applyMemoChange(remaining);
  } catch (error) {
    if (session === account) {
      await refreshMemos();
      syncStatus(error.status ? error.message : '删除失败，请检查网络后重试。');
    }
  } finally { button.disabled = false; }
}
$('#memoList').addEventListener('click',handleListAction);

applySettings(); renderLists(); resetMemoSync();
if (account) {
  $('#accountButton').textContent = '退出登录';
  waiting = true;
  restoreHistory().catch(error => { $('#speechHint').textContent = error.message; })
    .finally(() => { waiting = false; $('#sendButton').disabled = !input.value.trim(); });
}
if(location.protocol.startsWith('http') && 'serviceWorker' in navigator) navigator.serviceWorker.register('./service-worker.js');
