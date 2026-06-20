function maskKey(k) {
  return (!k || k.length < 8) ? k : (k.slice(0, 6) + '\u2026' + k.slice(-4));
}

function copyText(text) {
  const timeout = new Promise(function(_, reject) {
    setTimeout(function() { reject(new Error('copy timeout')); }, 1000);
  });
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return Promise.race([navigator.clipboard.writeText(text), timeout]);
  }
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try {
    document.execCommand('copy');
    return Promise.resolve();
  } catch (e) {
    return Promise.reject(e);
  } finally {
    document.body.removeChild(ta);
  }
}

function generateKey() {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  var sk = 'sk-';
  for (var i = 0; i < 48; i++) sk += chars.charAt(Math.floor(Math.random() * chars.length));
  document.getElementById('kKey').value = sk;
}

async function loadKeys() {
  const d = await API.keys.list();
  const keys = d.keys || [];
  const tbody = document.getElementById('keysBody');
  tbody.textContent = '';
  if (!keys.length) {
    var tr = document.createElement('tr');
    var td = document.createElement('td');
    td.colSpan = 4;
    td.style.cssText = 'padding:24px;text-align:center;color:var(--text-dim);';
    td.textContent = '\u6682\u65E0\u5BC6\u94A5';
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }
  keys.forEach(function(k) {
    var tr = document.createElement('tr');
    var nameTd = document.createElement('td');
    nameTd.className = 'mono-cell';
    nameTd.textContent = k.name;
    tr.appendChild(nameTd);

    var keyTd = document.createElement('td');
    keyTd.className = 'key-cell';
    var maskedSpan = document.createElement('span');
    maskedSpan.className = 'masked';
    maskedSpan.textContent = maskKey(k.key);
    keyTd.appendChild(maskedSpan);
    var copyBtn = document.createElement('button');
    copyBtn.className = 'action-copy-btn';
    copyBtn.dataset.key = k.key;
    copyBtn.title = '\u590D\u5236\u5BC6\u94A5';
    copyBtn.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    copyBtn.onclick = function(e) {
      e.stopPropagation();
      copyText(copyBtn.dataset.key).then(function() { toast('\u5DF2\u590D\u5236'); }).catch(function() { toast('\u590D\u5236\u5931\u8D25\uFF0C\u8BF7\u624B\u52A8\u590D\u5236'); });
    };
    keyTd.appendChild(copyBtn);
    tr.appendChild(keyTd);

    var descTd = document.createElement('td');
    descTd.style.cssText = 'color:var(--text-dim);';
    descTd.textContent = k.description || '\u2014';
    tr.appendChild(descTd);

    var actionTd = document.createElement('td');
    actionTd.style.cssText = 'text-align:right;';
    var delBtn = document.createElement('button');
    delBtn.className = 'btn danger';
    delBtn.textContent = '\u5220\u9664';
    delBtn.onclick = function() { delKey(k.name); };
    actionTd.appendChild(delBtn);
    tr.appendChild(actionTd);

    tbody.appendChild(tr);
  });
}

async function addKey() {
  var name = document.getElementById('kName').value.trim();
  var key = document.getElementById('kKey').value.trim();
  var desc = document.getElementById('kDesc').value.trim();
  if (!name) return toast('\u8BF7\u586B\u540D\u79F0');
  try {
    const resp = await API.keys.add(name, key, desc);
    document.getElementById('kName').value = '';
    document.getElementById('kKey').value = '';
    document.getElementById('kDesc').value = '';
    await loadKeys();
    if (resp && resp.key) {
      document.getElementById('kKey').value = resp.key;
      try {
        await copyText(resp.key);
        toast('\u5DF2\u6DFB\u52A0\u5E76\u590D\u5236\u5B8C\u6574 Key');
      } catch (e) {
        toast('\u5DF2\u6DFB\u52A0\uFF0C\u5B8C\u6574 Key \u5DF2\u7559\u5728\u8F93\u5165\u6846');
      }
    } else {
      toast('\u5DF2\u6DFB\u52A0');
    }
  } catch (e) {
    toast('\u6DFB\u52A0\u5931\u8D25: ' + e.message);
  }
}

async function delKey(name) {
  if (!confirm('\u5220\u9664\u5BC6\u94A5 ' + name + '\uFF1F')) return;
  await API.keys.del(name);
  loadKeys();
  toast('\u5DF2\u5220\u9664');
}
