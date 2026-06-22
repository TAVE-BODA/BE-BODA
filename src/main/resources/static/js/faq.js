const FAQ = [
    { cat:'upload',   q:'PDF 파일이 아니면 올릴 수 없나요?',              a:'네, 현재 PDF만 지원해요. 보험사 앱이나 홈페이지에서 PDF로 받아 올려주세요.' },
    { cat:'upload',   q:'보험증권만 올려도 되나요?',                      a:'증권만으로도 기본 분석이 가능해요. 약관을 추가하면 보장 금액, 면책 조항 등 상세 분석이 제공돼요.' },
    { cat:'upload',   q:'스캔본 PDF도 올릴 수 있나요?',                   a:'스캔 품질에 따라 분석이 어려울 수 있어요. 보험사 앱에서 전자 PDF로 받아 올려주세요.' },
    { cat:'upload',   q:'파일 크기 제한이 있나요?',                       a:'최대 20MB까지 올릴 수 있어요.' },
    { cat:'analysis', q:'분석 결과는 얼마나 정확한가요?',                  a:'증권과 약관에 명시된 내용 기반으로 분석해요. 실제 보험금은 청구 심사 후 확정되므로 참고용으로만 활용해주세요.' },
    { cat:'analysis', q:'분석 도중 창을 닫아도 괜찮나요?',                a:'네! 특히 약관은 분량이 많아 시간이 걸려요. 창을 열어두면 돌아왔을 때 이어서 볼 수 있어요.' },
    { cat:'privacy',  q:'업로드한 파일은 어떻게 되나요?',                 a:'PDF 원본은 텍스트 추출 즉시 삭제돼요. 주민번호 등 민감정보는 마스킹 후 분석 텍스트만 보관돼요.' },
    { cat:'privacy',  q:'개인정보가 다른 곳에 공유되나요?',               a:'분석 목적으로만 사용되며 제3자에게 공유되지 않아요.' },
    { cat:'support',  q:'지원하는 보험사가 어디어디인가요?',              a:'삼성생명, 한화생명, 교보생명, 현대해상, DB손해보험 등 대부분의 주요 보험사 PDF를 분석할 수 있어요.' },
    { cat:'support',  q:'보다가 답하기 어려운 질문은 어떤 건가요?',       a:'중복 보상, 비례 보상 등 복잡한 케이스는 보험사 고객센터에 직접 확인하는 것이 정확해요.' },
];

let currentCat = 'all';

function render(data) {
    const list = document.getElementById('faq-list');
    if (!data.length) { list.innerHTML = '<p style="color:var(--text-muted);padding:20px 0">검색 결과가 없어요.</p>'; return; }
    list.innerHTML = data.map(f => `
        <div class="faq-item" data-cat="${f.cat}">
            <div class="faq-question" onclick="toggle(this)"><span>${f.q}</span><span>＋</span></div>
            <div class="faq-answer">${f.a}</div>
        </div>`).join('');
}

function toggle(el) {
    const ans = el.nextElementSibling, open = ans.classList.contains('open');
    document.querySelectorAll('.faq-answer.open').forEach(a => a.classList.remove('open'));
    document.querySelectorAll('.faq-question span:last-child').forEach(s => s.textContent = '＋');
    if (!open) { ans.classList.add('open'); el.querySelector('span:last-child').textContent = '－'; }
}

function filterCat(cat, btn) {
    currentCat = cat;
    document.querySelectorAll('.cat-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    apply();
}

function filterFaq(q) { apply(q); }

function apply(q = document.getElementById('faq-search').value) {
    let data = currentCat === 'all' ? FAQ : FAQ.filter(f => f.cat === currentCat);
    if (q) { const lq = q.toLowerCase(); data = data.filter(f => f.q.toLowerCase().includes(lq) || f.a.toLowerCase().includes(lq)); }
    render(data);
}

render(FAQ);
