const ICONS = { '진단비':'🏥','수술비':'🔪','입원비':'🛏️','골절·재해':'🦴','생활·특수':'🛡️','치아':'🦷' };

async function loadDashboard() {
    try {
        const data = await (await fetch('/api/dashboard')).json();
        if (!data.ready) { setTimeout(loadDashboard, 3000); return; }
        document.getElementById('loading-state').classList.add('hidden');
        const r = data.data;
        if (r.insurerName) document.getElementById('dashboard-title').textContent = `${r.insurerName} 보험 분석 결과`;
        if (r.estimatedAmount) {
            const b = document.getElementById('dashboard-amount-badge');
            b.textContent = `최대 ${r.estimatedAmount}`; b.classList.remove('hidden');
        }
        renderCards(r.cards || []);
    } catch { document.getElementById('loading-state').innerHTML = '<p>데이터를 불러오지 못했어요.</p>'; }
}

function renderCards(cards) {
    const grid = document.getElementById('card-grid');
    grid.classList.remove('hidden');
    if (!cards.length) { grid.innerHTML = '<p style="color:var(--text-muted)">감지된 보장 항목이 없어요.</p>'; return; }
    grid.innerHTML = cards.map((c, i) => `
        <div class="coverage-card" onclick="showDetail(${i})">
            <div class="card-type">${ICONS[c.type]||'📋'} ${c.type}</div>
            ${c.insurerTag ? `<div class="card-insurer">${c.insurerTag}</div>` : ''}
            <div class="card-amount">${(c.coverageAmount||'').replace(/\n/g,'<br>')}</div>
        </div>`).join('');
    window._cards = cards;
}

function showDetail(i) {
    const c = window._cards[i];
    let html = `<h2>${ICONS[c.type]||'📋'} ${c.type} 상세</h2>
        <h3 style="margin:16px 0 8px;font-size:14px;color:var(--text-muted)">보장 금액</h3>
        <p style="line-height:1.8">${(c.coverageAmount||'-').replace(/\n/g,'<br>')}</p>`;
    if (c.exclusions?.length)
        html += `<h3 style="margin:16px 0 8px;font-size:14px;color:var(--error)">면책 사항</h3><ul style="padding-left:18px;line-height:2">${c.exclusions.map(e=>`<li>${e}</li>`).join('')}</ul>`;
    if (c.evidenceText)
        html += `<h3 style="margin:16px 0 8px;font-size:14px;color:var(--text-muted)">약관 근거</h3><div class="evidence-box">${c.evidenceText}</div>`;
    if (!c.termsUploaded)
        html += `<div class="warn-banner" style="margin-top:16px">⚠️ 약관을 업로드하면 더 자세한 내용을 볼 수 있어요. <a href="/">약관 업로드 →</a></div>`;
    document.getElementById('detail-content').innerHTML = html;
    const p = document.getElementById('detail-panel');
    p.classList.remove('hidden');
    p.scrollIntoView({ behavior: 'smooth' });
}

function closeDetail() { document.getElementById('detail-panel').classList.add('hidden'); }

loadDashboard();
