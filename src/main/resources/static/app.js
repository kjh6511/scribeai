const realtimeTabBtn = document.getElementById('realtimeTabBtn');
const batchTabBtn = document.getElementById('batchTabBtn');
const realtimeView = document.getElementById('realtimeView');
const batchView = document.getElementById('batchView');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const summaryBtn = document.getElementById('summaryBtn');
const loadYoutubeBtn = document.getElementById('loadYoutubeBtn');
const youtubeUrlInput = document.getElementById('youtubeUrlInput');
const youtubeFrame = document.getElementById('youtubeFrame');
const realtimeDocumentIdInput = document.getElementById('realtimeDocumentIdInput');
const chunkMsInput = document.getElementById('chunkMsInput');
const wsStatus = document.getElementById('wsStatus');
const seqLabel = document.getElementById('seqLabel');
const captionPanel = document.getElementById('captionPanel');
const summaryPanel = document.getElementById('summaryPanel');
const logPanel = document.getElementById('logPanel');
const realtimeSummaryLoading = document.getElementById('realtimeSummaryLoading');
const realtimeSearchInput = document.getElementById('realtimeSearchInput');
const realtimeSearchBtn = document.getElementById('realtimeSearchBtn');
const realtimeSearchResetBtn = document.getElementById('realtimeSearchResetBtn');
const realtimeSearchResultPanel = document.getElementById('realtimeSearchResultPanel');
const realtimeQuestionList = document.getElementById('realtimeQuestionList');
const realtimeEvalQuestions = document.getElementById('realtimeEvalQuestions');
const realtimeEvalRunBtn = document.getElementById('realtimeEvalRunBtn');
const realtimeEvalResetBtn = document.getElementById('realtimeEvalResetBtn');
const realtimeEvalResultPanel = document.getElementById('realtimeEvalResultPanel');

const batchFileInput = document.getElementById('batchFileInput');
const batchYoutubeUrlInput = document.getElementById('batchYoutubeUrlInput');
const clearBatchInputBtn = document.getElementById('clearBatchInputBtn');
const runBatchBtn = document.getElementById('runBatchBtn');
const retryBatchBtn = document.getElementById('retryBatchBtn');
const batchLoading = document.getElementById('batchLoading');
const batchLoadingText = document.getElementById('batchLoadingText');
const batchSummaryPanel = document.getElementById('batchSummaryPanel');
const batchTranscriptPanel = document.getElementById('batchTranscriptPanel');
const batchLogPanel = document.getElementById('batchLogPanel');
const batchSearchInput = document.getElementById('batchSearchInput');
const batchSearchBtn = document.getElementById('batchSearchBtn');
const batchSearchResetBtn = document.getElementById('batchSearchResetBtn');
const batchSearchResultPanel = document.getElementById('batchSearchResultPanel');
const batchQuestionList = document.getElementById('batchQuestionList');
const batchEvalQuestions = document.getElementById('batchEvalQuestions');
const batchEvalRunBtn = document.getElementById('batchEvalRunBtn');
const batchEvalResetBtn = document.getElementById('batchEvalResetBtn');
const batchEvalResultPanel = document.getElementById('batchEvalResultPanel');

let stompClient = null;
let mediaRecorder = null;
let mediaStream = null;
let seq = 0;
let listening = false;
let recorderRotateTimer = null;
let listeningRealtimeDocumentId = null;
let wsConnectPromise = null;

let currentBatchDocumentId = null;
let batchPollTimer = null;
let batchPollStartedAt = 0;
let lastBatchStatus = '';
const BATCH_POLL_INTERVAL = 2500;
const BATCH_POLL_TIMEOUT = 180000;
let latestRealtimeSummary = null;
let latestBatchSummary = null;
let currentRealtimeDocumentId = null;
const indexedRagDocumentIds = new Set();
const ragIndexingPromises = new Map();

function log(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  logPanel.textContent += (logPanel.textContent ? '\n' : '') + line;
  logPanel.scrollTop = logPanel.scrollHeight;
}

function batchLog(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  batchLogPanel.textContent += (batchLogPanel.textContent ? '\n' : '') + line;
  batchLogPanel.scrollTop = batchLogPanel.scrollHeight;
}

function setWsStatus(status, isError) {
  wsStatus.textContent = status;
  wsStatus.className = isError ? 'status error' : (status === 'connected' ? 'status connected' : 'status');
}

function getRealtimeDocumentIdSilently() {
  const id = Number(realtimeDocumentIdInput.value);
  return (!id || Number.isNaN(id)) ? null : id;
}

function appendCaption(text) {
  captionPanel.textContent += (captionPanel.textContent ? '\n' : '') + text;
  captionPanel.scrollTop = captionPanel.scrollHeight;
}

function formatSections(sections) {
  if (!Array.isArray(sections) || sections.length === 0) return '섹션 없음';
  return sections.map((s, idx) => {
    const heading = (s && s.heading) ? s.heading : `섹션 ${idx + 1}`;
    const notes = (s && s.notes) ? s.notes : '(내용 없음)';
    return `[${idx + 1}] ${heading}\n${notes}`;
  }).join('\n\n');
}

function formatKeywords(keywords) {
  if (!Array.isArray(keywords) || keywords.length === 0) return '-';
  return keywords.join(', ');
}

function formatSuggestedQuestions(questions) {
  if (!Array.isArray(questions)) return [];
  return questions
    .map((q) => (q || '').trim())
    .filter((q) => q.length > 0);
}

function renderSummary(summary) {
  if (!summary || typeof summary !== 'object') {
    summaryPanel.textContent = '요약 결과 형식이 올바르지 않습니다.';
    return;
  }

  const title = summary.title || '요약 결과';
  const overview = summary.overview || '-';
  const finalSummary = summary.finalSummary || '-';
  const keywords = formatKeywords(summary.keywords);
  const aiComment = summary.aiComment || '관련 배경지식을 함께 보면 내용을 더 오래 기억하는 데 도움이 됩니다.';
  const sections = Array.isArray(summary.sections) ? summary.sections : [];
  const sectionHtml = sections.length
    ? sections.map((s, idx) => `
      <div class="section-item">
        <h4>${idx + 1}. ${escapeHtml((s && s.heading) ? s.heading : `섹션 ${idx + 1}`)}</h4>
        <p>${escapeHtml((s && s.notes) ? s.notes : '(내용 없음)')}</p>
      </div>
    `).join('')
    : '<p>섹션 정리 없음</p>';

  summaryPanel.innerHTML = `
    <div class="summary-plain">
      <h4>제목</h4>
      <p>${escapeHtml(title)}</p>
      <h4>개요</h4>
      <p>${escapeHtml(overview)}</p>
      <h4>정리</h4>
      ${sectionHtml}
      <h4>최종 요약</h4>
      <p>${escapeHtml(finalSummary)}</p>
      <h4>키워드</h4>
      <p>${escapeHtml(keywords)}</p>
      <h4>AI 코멘트</h4>
      <p>${escapeHtml(aiComment)}</p>
    </div>
  `;

  latestRealtimeSummary = summary;
  renderSuggestedQuestionChips(realtimeQuestionList, formatSuggestedQuestions(summary.suggestedQuestions), (question) => {
    realtimeSearchInput.value = question;
    runRealtimeSearch();
  });
}

function switchView(view) {
  const realtime = view === 'realtime';
  if (!realtime) {
    teardownRealtimeIfNeeded();
  }
  realtimeView.classList.toggle('hidden', !realtime);
  batchView.classList.toggle('hidden', realtime);
  realtimeTabBtn.classList.toggle('active', realtime);
  batchTabBtn.classList.toggle('active', !realtime);
}

function setBatchLoading(loading, message) {
  batchLoading.classList.toggle('hidden', !loading);
  runBatchBtn.disabled = loading;
  if (message) {
    batchLoadingText.textContent = message;
  }
}

function setRealtimeSummaryLoading(loading) {
  realtimeSummaryLoading.classList.toggle('hidden', !loading);
  summaryBtn.disabled = loading;
}

function stopBatchPolling() {
  if (batchPollTimer) {
    clearInterval(batchPollTimer);
    batchPollTimer = null;
  }
}

function hasBatchInput() {
  const file = batchFileInput.files && batchFileInput.files[0];
  const url = batchYoutubeUrlInput.value.trim();
  return Boolean(file || url);
}

function getBatchInputMode() {
  const file = batchFileInput.files && batchFileInput.files[0];
  const url = batchYoutubeUrlInput.value.trim();
  const hasFile = Boolean(file);
  const hasUrl = Boolean(url);

  if (hasFile && hasUrl) return 'both';
  if (hasFile) return 'file';
  if (hasUrl) return 'youtube';
  return 'none';
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function renderBatchSummary(summary) {
  if (!summary || typeof summary !== 'object') {
    batchSummaryPanel.textContent = '요약 형식이 올바르지 않습니다.';
    return;
  }

  const title = summary.title || '요약 결과';
  const overview = summary.overview || '개요 없음';
  const finalSummary = summary.finalSummary || '최종 요약 없음';
  const keywords = formatKeywords(summary.keywords);
  const aiComment = summary.aiComment || '관련 배경지식을 함께 보면 내용을 더 오래 기억하는 데 도움이 됩니다.';
  const sections = Array.isArray(summary.sections) ? summary.sections : [];

  const sectionHtml = sections.length
    ? sections.map((s, idx) => `
      <div class="section-item">
        <h4>${idx + 1}. ${escapeHtml(s.heading || `섹션 ${idx + 1}`)}</h4>
        <p>${escapeHtml(s.notes || '(내용 없음)')}</p>
      </div>
    `).join('')
    : '<p>섹션 정리 없음</p>';

  batchSummaryPanel.innerHTML = `
    <div class="summary-plain">
      <h4>제목</h4>
      <p>${escapeHtml(title)}</p>
      <h4>개요</h4>
      <p>${escapeHtml(overview)}</p>
      <h4>정리</h4>
      ${sectionHtml}
      <h4>최종 요약</h4>
      <p>${escapeHtml(finalSummary)}</p>
      <h4>키워드</h4>
      <p>${escapeHtml(keywords)}</p>
      <h4>AI 코멘트</h4>
      <p>${escapeHtml(aiComment)}</p>
    </div>
  `;

  latestBatchSummary = summary;
  renderSuggestedQuestionChips(batchQuestionList, formatSuggestedQuestions(summary.suggestedQuestions), (question) => {
    batchSearchInput.value = question;
    runBatchSearch();
  });
}

function renderBatchTranscript(transcript) {
  batchTranscriptPanel.textContent = transcript && transcript.trim()
    ? transcript
    : '영상 원문 결과가 없습니다.';
}

function renderSuggestedQuestionChips(container, questions, onClick) {
  if (!container) return;
  if (!questions || questions.length === 0) {
    container.innerHTML = '<span style="font-size:12px;color:#6a655d;">추천 질문이 아직 없습니다.</span>';
    return;
  }

  container.innerHTML = questions.map((question, index) =>
    `<button class="question-chip" data-question-index="${index}">${escapeHtml(question)}</button>`
  ).join('');

  Array.from(container.querySelectorAll('.question-chip')).forEach((button) => {
    const idx = Number(button.getAttribute('data-question-index'));
    button.addEventListener('click', () => onClick(questions[idx]));
  });
}

async function ensureRagIndex(documentId, logFn) {
  if (!documentId) {
    throw new Error('RAG 인덱싱 대상 documentId가 없습니다.');
  }
  if (indexedRagDocumentIds.has(documentId)) {
    return;
  }
  const pendingPromise = ragIndexingPromises.get(documentId);
  if (pendingPromise) {
    await pendingPromise;
    return;
  }
  await startRagIndexingInBackground(documentId, logFn);
}

function startRagIndexingInBackground(documentId, logFn, forceRebuild = false) {
  if (!documentId) {
    return Promise.resolve();
  }
  if (indexedRagDocumentIds.has(documentId)) {
    return Promise.resolve();
  }
  const pendingPromise = ragIndexingPromises.get(documentId);
  if (pendingPromise) {
    return pendingPromise;
  }

  const indexingPromise = (async () => {
    const forceQuery = forceRebuild ? '?force=true' : '';
    const res = await fetch(`/api/rag/index/documents/${documentId}${forceQuery}`, { method: 'POST' });
    const body = await res.json();
    if (!res.ok) {
      throw new Error(body.message || 'RAG 인덱싱 실패');
    }
    indexedRagDocumentIds.add(documentId);
    if (typeof logFn === 'function') {
      logFn(`RAG 인덱싱 완료: ${body.chunkCount} chunks`);
    }
  })()
    .catch((err) => {
      if (typeof logFn === 'function') {
        logFn(`RAG 인덱싱 실패: ${err.message || err}`);
      }
      throw err;
    })
    .finally(() => {
      ragIndexingPromises.delete(documentId);
    });

  ragIndexingPromises.set(documentId, indexingPromise);
  return indexingPromise;
}

function formatRagAnswerForDisplay(answer) {
  if (!answer || typeof answer !== 'string') {
    return '<p>답변을 생성하지 못했습니다.</p>';
  }

  let text = answer.replaceAll('\r', '').trim();
  text = text
    .replaceAll(/^[-•\s]*(핵심\s*답변|핵심답변|설명|근거)\s*[:：]\s*/gmi, '')
    .replaceAll(/\n{3,}/g, '\n\n')
    .replaceAll(/근거\s*:\s*#?[\d,\s#-]+$/gmi, '')
    .trim();

  const paragraphs = text
    .split(/\n{2,}/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (paragraphs.length === 0) {
    return '<p>답변을 생성하지 못했습니다.</p>';
  }

  return paragraphs.map((p) => `<p>${escapeHtml(p)}</p>`).join('');
}

function renderRagSourcesHtml(sources) {
  if (!Array.isArray(sources) || sources.length === 0) {
    return '<div class="rag-sources-empty">근거 데이터가 없습니다.</div>';
  }

  const items = sources.slice(0, 5).map((source, idx) => {
    const score = Number(source.score || 0);
    const scorePercent = Math.max(0, Math.min(100, Math.round(score * 100)));
    return `
      <article class="rag-source-item">
        <div class="rag-source-meta">${idx + 1}. chunk ${source.chunkIndex} · 정확도 ${scorePercent}</div>
        <div class="rag-source-text">${escapeHtml(source.content || '')}</div>
      </article>
    `;
  }).join('');

  return `
    <details class="rag-sources">
      <summary>근거 보기 (${Math.min(sources.length, 5)}개)</summary>
      <div class="rag-source-list">${items}</div>
    </details>
  `;
}

function renderBatchRagResult(query, response) {
  const sources = Array.isArray(response.sources) ? response.sources : [];
  batchSearchResultPanel.innerHTML = `
    <div class="rag-answer-wrap">
      <div class="rag-question">${escapeHtml(query)}</div>
      <div class="rag-answer">${formatRagAnswerForDisplay(response.answer)}</div>
      ${renderRagSourcesHtml(sources)}
    </div>
  `;
}

function renderSearchError(panel, message) {
  panel.textContent = message || '검색 처리 중 오류가 발생했습니다.';
}

function renderRealtimeRagResult(query, response) {
  const sources = Array.isArray(response.sources) ? response.sources : [];
  realtimeSearchResultPanel.innerHTML = `
    <div class="rag-answer-wrap">
      <div class="rag-question">${escapeHtml(query)}</div>
      <div class="rag-answer">${formatRagAnswerForDisplay(response.answer)}</div>
      ${renderRagSourcesHtml(sources)}
    </div>
  `;
}

async function runRealtimeSearch() {
  const query = realtimeSearchInput.value.trim();
  if (!query) {
    realtimeSearchResultPanel.textContent = '검색어를 입력하세요.';
    return;
  }
  if (!currentRealtimeDocumentId) {
    realtimeSearchResultPanel.textContent = '실시간 documentId를 찾을 수 없습니다. 실시간 문서를 다시 생성해 주세요.';
    return;
  }

  realtimeSearchResultPanel.textContent = 'RAG 검색 중입니다...';
  try {
    await ensureRagIndex(currentRealtimeDocumentId, log);
    const res = await fetch('/api/rag/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        documentId: currentRealtimeDocumentId,
        question: query,
        topK: 7,
        autoIndex: true
      })
    });
    const body = await res.json();
    if (!res.ok) {
      renderSearchError(realtimeSearchResultPanel, body.message || 'RAG 답변 생성 실패');
      return;
    }
    renderRealtimeRagResult(query, body);
  } catch (err) {
    renderSearchError(realtimeSearchResultPanel, err.message || String(err));
  }
}

async function runBatchSearch() {
  const query = batchSearchInput.value.trim();
  if (!query) {
    batchSearchResultPanel.textContent = '검색어를 입력하세요.';
    return;
  }
  if (!currentBatchDocumentId) {
    batchSearchResultPanel.textContent = '먼저 업로드 · 유튜브 요약을 완료해 주세요.';
    return;
  }

  batchSearchResultPanel.textContent = 'RAG 검색 중입니다...';
  try {
    await ensureRagIndex(currentBatchDocumentId, batchLog);
    const res = await fetch('/api/rag/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        documentId: currentBatchDocumentId,
        question: query,
        topK: 7,
        autoIndex: true
      })
    });
    const body = await res.json();
    if (!res.ok) {
      renderSearchError(batchSearchResultPanel, body.message || 'RAG 답변 생성 실패');
      return;
    }
    renderBatchRagResult(query, body);
  } catch (err) {
    renderSearchError(batchSearchResultPanel, err.message || String(err));
  }
}

function resetRealtimeSearch() {
  realtimeSearchInput.value = '';
  realtimeSearchResultPanel.textContent = '검색 결과가 여기에 표시됩니다.';
}

function resetBatchSearch() {
  batchSearchInput.value = '';
  batchSearchResultPanel.textContent = '검색 결과가 여기에 표시됩니다.';
}

function getDefaultEvalQuestions() {
  return [
    '이 영상의 핵심 주제는 무엇인가요?',
    '발표자가 가장 강조한 근거는 무엇인가요?',
    '핵심 내용을 한 문단으로 정리해 주세요.',
    '실제 적용 포인트가 있다면 무엇인가요?',
    '오해하기 쉬운 부분이나 주의점은 무엇인가요?'
  ];
}

function parseEvalQuestions(inputValue) {
  const fromInput = (inputValue || '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  const questions = fromInput.length > 0 ? fromInput : getDefaultEvalQuestions();
  const dedupQuestions = Array.from(new Set(questions)).slice(0, 5);
  return {
    questions: dedupQuestions,
    questionSet: dedupQuestions.map((question) => ({ question }))
  };
}

function formatLatency(msValue) {
  const ms = Number(msValue || 0);
  const sec = (ms / 1000).toFixed(2);
  return `${ms}ms (${sec}s)`;
}

function modeLabel(mode) {
  if (mode === 'VECTOR_ONLY') return 'Vector Only';
  if (mode === 'HYBRID_ONLY') return 'Hybrid Only';
  if (mode === 'HYBRID_RERANK') return 'Hybrid + Rerank';
  return mode || '-';
}

function renderEvaluationResult(panel, result) {
  const successRate = result.totalQuestions > 0
    ? Math.round((result.successCount / result.totalQuestions) * 100)
    : 0;

  const items = Array.isArray(result.items) ? result.items : [];
  const rows = items.map((item) => {
    const status = item.status === 'SUCCESS' ? '성공' : '실패';
    const error = item.errorMessage
      ? `<div class="err">오류: ${escapeHtml(item.errorMessage)}</div>`
      : '';
    return `
      <div class="eval-item">
        <div class="meta">#${item.index} · ${status} · ${formatLatency(item.latencyMs)}</div>
        <div class="q">${escapeHtml(item.question || '')}</div>
        ${error}
      </div>
    `;
  }).join('');

  const comparisons = Array.isArray(result.comparisons) ? result.comparisons : [];
  const comparisonRows = comparisons.map((cmp) => `
    <tr>
      <td>${escapeHtml(modeLabel(cmp.mode))}</td>
      <td>${cmp.successCount}/${cmp.totalQuestions}</td>
      <td>${formatLatency(cmp.avgLatencyMs)}</td>
      <td>${formatLatency(cmp.p95LatencyMs)}</td>
      <td>${cmp.labeledQuestionCount > 0 ? `${((cmp.hitRateAtK || 0) * 100).toFixed(1)}%` : '-'}</td>
      <td>${cmp.labeledQuestionCount > 0 ? Number(cmp.mrrAtK || 0).toFixed(3) : '-'}</td>
      <td>${cmp.labeledQuestionCount > 0 ? Number(cmp.ndcgAtK || 0).toFixed(3) : '-'}</td>
    </tr>
  `).join('');

  panel.innerHTML = `
    <div class="eval-summary">
      <div class="eval-metric">
        <div class="label">성공률</div>
        <div class="value">${successRate}%</div>
      </div>
      <div class="eval-metric">
        <div class="label">평균 지연</div>
        <div class="value">${formatLatency(result.avgLatencyMs)}</div>
      </div>
      <div class="eval-metric">
        <div class="label">P95 지연</div>
        <div class="value">${formatLatency(result.p95LatencyMs)}</div>
      </div>
      <div class="eval-metric">
        <div class="label">예상 호출량</div>
        <div class="value">${result.estimatedTotalCalls}</div>
      </div>
      ${result.labeledQuestionCount > 0 ? `
      <div class="eval-metric">
        <div class="label">Hit@K</div>
        <div class="value">${((result.hitRateAtK || 0) * 100).toFixed(1)}%</div>
      </div>
      <div class="eval-metric">
        <div class="label">MRR@K</div>
        <div class="value">${Number(result.mrrAtK || 0).toFixed(3)}</div>
      </div>
      <div class="eval-metric">
        <div class="label">nDCG@K</div>
        <div class="value">${Number(result.ndcgAtK || 0).toFixed(3)}</div>
      </div>
      ` : ''}
    </div>
    <div style="font-size:12px;color:#6a655d;margin-bottom:8px;">
      임베딩 ${result.estimatedEmbeddingCalls}회 · 답변 ${result.estimatedAnswerCalls}회
      ${result.chunkCountUsedForIndexing > 0 ? ` · 인덱싱 청크 ${result.chunkCountUsedForIndexing}개` : ''}
      ${result.labeledQuestionCount > 0 ? ` · 라벨 평가 ${result.labeledQuestionCount}문항` : ''}
    </div>
    ${result.labeledQuestionCount > 0 ? `
    <div style="font-size:12px;color:#6a655d;margin:0 0 8px;">
      Hit@K: 정답 청크가 K개 결과 안에 포함된 비율 ·
      MRR@K: 정답이 상위에 나올수록 높아지는 순위 점수 ·
      nDCG@K: 순위 품질을 반영한 정규화 점수
    </div>
    ` : ''}
    ${comparisons.length > 0 ? `
    <div class="eval-compare-wrap">
      <div class="eval-compare-title">모드 비교</div>
      <table class="eval-compare-table">
        <thead>
          <tr>
            <th>Mode</th>
            <th>성공</th>
            <th>평균</th>
            <th>P95</th>
            <th>Hit@K</th>
            <th>MRR@K</th>
            <th>nDCG@K</th>
          </tr>
        </thead>
        <tbody>
          ${comparisonRows}
        </tbody>
      </table>
    </div>
    ` : ''}
    <div>${rows || '<div class="eval-item">결과가 없습니다.</div>'}</div>
  `;
}

async function runEvaluation(documentId, questionInput, resultPanel, logFn) {
  if (!documentId) {
    resultPanel.textContent = '평가할 문서가 없습니다. 먼저 요약을 완료해 주세요.';
    return;
  }

  const parsed = parseEvalQuestions(questionInput.value);
  const questions = parsed.questions;
  const questionSet = parsed.questionSet;
  questionInput.value = questions.join('\n');
  resultPanel.textContent = '평가 실행 중입니다...';

  try {
    const res = await fetch('/api/rag/evaluations/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        documentId,
        questions,
        questionSet,
        compareModes: ['VECTOR_ONLY', 'HYBRID_ONLY', 'HYBRID_RERANK'],
        topK: 7,
        autoIndex: true
      })
    });
    const body = await res.json();
    if (!res.ok) {
      resultPanel.textContent = body.message || '평가 실행 실패';
      if (typeof logFn === 'function') {
        logFn(`평가 실패: ${body.message || 'unknown'}`);
      }
      return;
    }
    renderEvaluationResult(resultPanel, body);
    if (typeof logFn === 'function') {
      const labelMsg = body.labeledQuestionCount > 0
        ? ` · Hit@K ${((body.hitRateAtK || 0) * 100).toFixed(1)}%`
        : '';
      logFn(`평가 완료: ${body.successCount}/${body.totalQuestions} 성공${labelMsg}`);
    }
  } catch (err) {
    resultPanel.textContent = `평가 오류: ${err.message || err}`;
    if (typeof logFn === 'function') {
      logFn(`평가 오류: ${err.message || err}`);
    }
  }
}

function resetEvaluation(questionInput, panel) {
  questionInput.value = '';
  panel.textContent = '평가 결과가 여기에 표시됩니다.';
}

async function createRealtimeDocument() {
  const previousDocumentId = currentRealtimeDocumentId;
  const res = await fetch('/api/realtime/documents', { method: 'POST' });
  if (!res.ok) {
    throw new Error('실시간 문서 생성 실패');
  }
  const data = await res.json();
  currentRealtimeDocumentId = data.documentId || null;
  if (previousDocumentId) {
    indexedRagDocumentIds.delete(previousDocumentId);
    ragIndexingPromises.delete(previousDocumentId);
  }
  realtimeDocumentIdInput.value = currentRealtimeDocumentId ? String(currentRealtimeDocumentId) : '';
  captionPanel.textContent = '';
  summaryPanel.textContent = '아직 요약이 없습니다.';
  latestRealtimeSummary = null;
  renderSuggestedQuestionChips(realtimeQuestionList, [], () => {});
  resetRealtimeSearch();
  resetEvaluation(realtimeEvalQuestions, realtimeEvalResultPanel);
  seq = 0;
  seqLabel.textContent = '0';
  log(`실시간 문서 생성: ${data.documentId}`);
}

function connectWs() {
  const documentId = getRealtimeDocumentIdSilently();
  if (!documentId) return Promise.resolve(false);

  if (stompClient && stompClient.connected) {
    return Promise.resolve(true);
  }
  if (wsConnectPromise) {
    return wsConnectPromise;
  }

  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const brokerURL = `${protocol}://${location.host}/api/ws`;

  wsConnectPromise = new Promise((resolve, reject) => {
    stompClient = new StompJs.Client({
      brokerURL,
      reconnectDelay: 3000,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
    splitLargeFrames: true,
    maxWebSocketChunkSize: 16 * 1024,
    debug: () => {}
  });

    stompClient.onConnect = () => {
      setWsStatus('connected', false);
      log(`WS 연결 성공`);

      stompClient.subscribe(`/topic/realtime/${documentId}/captions`, (msg) => {
        const body = JSON.parse(msg.body);
        appendCaption(body.text || '');
      });

      stompClient.subscribe(`/topic/realtime/${documentId}/summaries`, (msg) => {
        const body = JSON.parse(msg.body);
        setRealtimeSummaryLoading(false);
        renderSummary(body.summary);
        log('요약 push 수신');
      });

      stompClient.subscribe(`/topic/realtime/${documentId}/errors`, (msg) => {
        const body = JSON.parse(msg.body);
        log(`오류: ${body.message}`);
      });
      wsConnectPromise = null;
      resolve(true);
    };

    stompClient.onStompError = (frame) => {
      wsConnectPromise = null;
      setWsStatus('stomp-error', true);
      const message = `STOMP 오류: ${frame.headers.message || ''}`;
      log(message);
      reject(new Error(message));
    };

    stompClient.onWebSocketError = () => {
      wsConnectPromise = null;
      setWsStatus('ws-error', true);
      log('WebSocket 오류');
    };

    stompClient.onWebSocketClose = () => {
      setWsStatus('disconnected', false);
      log('WebSocket 닫힘');
    };

    stompClient.onDisconnect = () => {
      setWsStatus('disconnected', false);
      log('WS 연결 종료');
    };

    stompClient.activate();
  });
  return wsConnectPromise;
}

async function ensureRealtimeReady() {
  if (!getRealtimeDocumentIdSilently()) {
    await createRealtimeDocument();
  }
  if (!stompClient || !stompClient.connected) {
    const connected = await connectWs();
    if (!connected) {
      throw new Error('WS 연결 실패');
    }
  }
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result;
      if (typeof result !== 'string') {
        reject(new Error('base64 인코딩 실패'));
        return;
      }
      resolve(result.split(',')[1] || '');
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

async function startListening() {
  await ensureRealtimeReady();
  const documentId = getRealtimeDocumentIdSilently();
  if (!documentId) return;
  if (listening) {
    log('이미 듣기 중입니다.');
    return;
  }

  const chunkMs = Number(chunkMsInput.value) || 5000;
  mediaStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });

  const audioTrack = mediaStream.getAudioTracks()[0];
  if (!audioTrack) {
    alert('공유 화면에서 오디오를 포함해 주세요.');
    mediaStream.getTracks().forEach(t => t.stop());
    mediaStream = null;
    return;
  }

  const audioStream = new MediaStream([audioTrack]);
  mediaRecorder = new MediaRecorder(audioStream, {
    mimeType: 'audio/webm;codecs=opus',
    audioBitsPerSecond: 32000
  });
  listeningRealtimeDocumentId = documentId;

  mediaRecorder.ondataavailable = async (event) => {
    if (!event.data || event.data.size === 0) return;
    if (event.data.size < 500) {
      log(`수신 청크가 작아 무시: ${event.data.size} bytes`);
      return;
    }
    try {
      log(`청크 수신: ${event.data.size} bytes`);
      const base64Audio = await blobToBase64(event.data);
      if (base64Audio.length > 2500000) {
        log(`청크 크기 초과로 전송 생략: ${base64Audio.length}`);
        return;
      }
      seq += 1;
      seqLabel.textContent = String(seq);
      stompClient.publish({
        destination: `/app/realtime/${listeningRealtimeDocumentId}/audio`,
        body: JSON.stringify({
          sequence: seq,
          fileName: `chunk-${seq}.webm`,
          contentType: event.data.type || 'audio/webm',
          base64Audio
        })
      });
    } catch (err) {
      log(`청크 전송 실패: ${err.message || err}`);
    }
  };

  mediaRecorder.onstop = () => {
    if (!listening || !mediaRecorder) return;
    try {
      mediaRecorder.start();
    } catch (err) {
      log(`녹음 재시작 실패: ${err.message || err}`);
    }
  };

  mediaRecorder.start();
  recorderRotateTimer = setInterval(() => {
    if (!mediaRecorder || mediaRecorder.state !== 'recording') return;
    try {
      mediaRecorder.stop();
    } catch (err) {
      log(`청크 회전 실패: ${err.message || err}`);
    }
  }, chunkMs);
  listening = true;
  log('듣기 시작');
}

function stopListening() {
  const documentId = getRealtimeDocumentIdSilently();
  if (!documentId) return;

  listening = false;
  if (recorderRotateTimer) {
    clearInterval(recorderRotateTimer);
    recorderRotateTimer = null;
  }

  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop();
  }
  mediaRecorder = null;
  if (mediaStream) {
    mediaStream.getTracks().forEach(t => t.stop());
    mediaStream = null;
  }

  if (stompClient && stompClient.connected) {
    stompClient.publish({
      destination: `/app/realtime/${documentId}/finish`,
      body: '{}'
    });
  }

  listeningRealtimeDocumentId = null;
  log('듣기 중단');
}

function disconnectWs() {
  wsConnectPromise = null;
  if (stompClient) {
    try {
      stompClient.deactivate();
    } catch (e) {
      log(`WS 종료 오류: ${e.message || e}`);
    }
  }
  stompClient = null;
  setWsStatus('disconnected', false);
}

function teardownRealtimeIfNeeded() {
  if (listening) {
    stopListening();
  }
  if (stompClient && stompClient.connected) {
    disconnectWs();
    log('탭 전환으로 실시간 연결 종료');
  }
}

async function summarizeNow() {
  const documentId = getRealtimeDocumentIdSilently();
  if (!documentId) return;
  setRealtimeSummaryLoading(true);
  try {
    const res = await fetch(`/api/realtime/documents/${documentId}/summaries`, { method: 'POST' });
    const body = await res.json();

    if (!res.ok) {
      log(`요약 실패: ${body.message || 'unknown'}`);
      return;
    }

    renderSummary(body.summary);
    log('요약 완료');
  } finally {
    setRealtimeSummaryLoading(false);
  }
}

function toYouTubeEmbedUrl(url) {
  try {
    const u = new URL(url);
    if (u.hostname.includes('youtu.be')) {
      const id = u.pathname.replace('/', '').trim();
      return id ? `https://www.youtube.com/embed/${id}?autoplay=1` : null;
    }
    if (u.hostname.includes('youtube.com')) {
      const id = u.searchParams.get('v');
      return id ? `https://www.youtube.com/embed/${id}?autoplay=1` : null;
    }
    return null;
  } catch {
    return null;
  }
}

function loadYouTube() {
  const url = youtubeUrlInput.value.trim();
  const embedUrl = toYouTubeEmbedUrl(url);
  if (!embedUrl) {
    alert('올바른 YouTube URL을 입력하세요.');
    return;
  }
  youtubeFrame.src = embedUrl;
  log('YouTube 영상 불러오기 완료');
}

async function fetchBatchDocument(documentId) {
  const res = await fetch(`/api/batch/jobs/${documentId}`);
  const body = await res.json();
  if (!res.ok) {
    throw new Error(body.message || '배치 조회 실패');
  }
  return body;
}

async function refreshBatchDocumentStatus(documentId) {
  const body = await fetchBatchDocument(documentId);
  if (body.status && body.status !== lastBatchStatus) {
    batchLog(`현재 상태: ${body.status}`);
    lastBatchStatus = body.status;
  }

  if (body.status === 'DONE') {
    stopBatchPolling();
    setBatchLoading(false);
    retryBatchBtn.disabled = true;
    renderBatchTranscript(body.transcript || '');
    renderBatchSummary(body.summaryJson || {});
    batchLog('요약이 완료되었습니다.');
    return true;
  }

  if (body.status === 'FAIL') {
    stopBatchPolling();
    setBatchLoading(false);
    retryBatchBtn.disabled = false;
    batchSummaryPanel.textContent = `요약 실패: ${body.errorMessage || '알 수 없는 오류'}`;
    batchLog(`요약 실패: ${body.errorMessage || '알 수 없는 오류'}`);
    return true;
  }

  return false;
}

function startBatchPolling(documentId) {
  stopBatchPolling();
  batchPollStartedAt = Date.now();
  lastBatchStatus = '';
  setBatchLoading(true, '요약 중입니다. 잠시만 기다려 주세요.');
  batchLog('요약 작업을 시작했습니다.');

  batchPollTimer = setInterval(async () => {
    const elapsedSec = Math.floor((Date.now() - batchPollStartedAt) / 1000);
    setBatchLoading(true, `요약 중입니다... (${elapsedSec}초)`);

    if (Date.now() - batchPollStartedAt > BATCH_POLL_TIMEOUT) {
      stopBatchPolling();
      setBatchLoading(false);
      retryBatchBtn.disabled = true;
      batchLog('처리 시간이 길어 자동 조회를 중단했습니다. FAIL이 아니면 재시도할 수 없습니다. 요약하기를 다시 눌러 상태를 확인해 주세요.');
      return;
    }

    try {
      await refreshBatchDocumentStatus(documentId);
    } catch (err) {
      stopBatchPolling();
      setBatchLoading(false);
      retryBatchBtn.disabled = false;
      batchLog(`조회 오류: ${err.message || err}`);
    }
  }, BATCH_POLL_INTERVAL);
}

async function runBatchSummary() {
  if (!hasBatchInput() && currentBatchDocumentId) {
    batchLog('기존 작업 상태를 다시 확인합니다.');
    startBatchPolling(currentBatchDocumentId);
    return;
  }

  const file = batchFileInput.files && batchFileInput.files[0];
  const url = batchYoutubeUrlInput.value.trim();
  const mode = getBatchInputMode();

  if (mode === 'none') {
    alert('파일 또는 유튜브 링크 중 하나를 입력하세요.');
    return;
  }
  if (mode === 'both') {
    alert('파일과 유튜브 링크를 동시에 사용할 수 없습니다. 하나만 입력해 주세요.');
    batchLog('요약 시작 중단: 파일 또는 유튜브 중 하나만 선택해야 합니다.');
    return;
  }

  let res;
  if (mode === 'file') {
    const form = new FormData();
    form.append('file', file);
    res = await fetch('/api/batch/jobs', { method: 'POST', body: form });
  } else {
    res = await fetch('/api/batch/jobs/youtube', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    });
  }

  const body = await res.json();
  if (!res.ok) {
    batchLog(`요약 시작 실패: ${body.message || 'unknown'}`);
    return;
  }

  currentBatchDocumentId = body.documentId;
  indexedRagDocumentIds.delete(currentBatchDocumentId);
  ragIndexingPromises.delete(currentBatchDocumentId);
  retryBatchBtn.disabled = true;
  batchSummaryPanel.textContent = '요약 결과를 불러오는 중입니다...';
  batchTranscriptPanel.textContent = '영상 원문을 불러오는 중입니다...';
  latestBatchSummary = null;
  renderSuggestedQuestionChips(batchQuestionList, [], () => {});
  resetBatchSearch();
  resetEvaluation(batchEvalQuestions, batchEvalResultPanel);
  startBatchPolling(currentBatchDocumentId);
}

async function retryBatchJob() {
  const documentId = Number(currentBatchDocumentId);
  if (!documentId) {
    alert('다시 시도할 작업이 없습니다.');
    return;
  }

  const current = await fetchBatchDocument(documentId);
  if (current.status !== 'FAIL') {
    batchLog(`재시도는 FAIL 상태에서만 가능합니다. 현재 상태: ${current.status}`);
    if (current.status === 'PENDING' || current.status === 'RUNNING') {
      startBatchPolling(documentId);
    }
    return;
  }

  const res = await fetch(`/api/batch/jobs/${documentId}/retry`, { method: 'POST' });
  const body = await res.json();
  if (!res.ok) {
    batchLog(`재시도 실패: ${body.message || 'unknown'}`);
    return;
  }

  retryBatchBtn.disabled = true;
  indexedRagDocumentIds.delete(documentId);
  ragIndexingPromises.delete(documentId);
  batchSummaryPanel.textContent = '재시도 중입니다...';
  batchLog('재시도 요청 완료');
  startBatchPolling(documentId);
}

function clearBatchInputs() {
  batchFileInput.value = '';
  batchYoutubeUrlInput.value = '';
  if (currentBatchDocumentId) {
    indexedRagDocumentIds.delete(currentBatchDocumentId);
    ragIndexingPromises.delete(currentBatchDocumentId);
  }
  latestBatchSummary = null;
  renderSuggestedQuestionChips(batchQuestionList, [], () => {});
  resetBatchSearch();
  resetEvaluation(batchEvalQuestions, batchEvalResultPanel);
  batchLog('입력값을 초기화했습니다.');
}

function clearConversationMemoryOnUnload() {
  const targets = new Set();
  if (currentRealtimeDocumentId) {
    targets.add(currentRealtimeDocumentId);
  }
  if (currentBatchDocumentId) {
    targets.add(currentBatchDocumentId);
  }
  if (targets.size === 0) {
    return;
  }

  targets.forEach((documentId) => {
    fetch(`/api/rag/conversations/documents/${documentId}/clear`, {
      method: 'POST',
      keepalive: true
    }).catch(() => {});
  });
}

realtimeTabBtn.addEventListener('click', () => switchView('realtime'));
batchTabBtn.addEventListener('click', () => switchView('batch'));
startBtn.addEventListener('click', () => startListening().catch(err => log(`듣기 시작 오류: ${err.message || err}`)));
stopBtn.addEventListener('click', stopListening);
summaryBtn.addEventListener('click', () => summarizeNow().catch(err => log(`요약 오류: ${err.message || err}`)));
loadYoutubeBtn.addEventListener('click', loadYouTube);
runBatchBtn.addEventListener('click', () => runBatchSummary().catch(err => batchLog(`요약 오류: ${err.message || err}`)));
retryBatchBtn.addEventListener('click', () => retryBatchJob().catch(err => batchLog(`재시도 오류: ${err.message || err}`)));
clearBatchInputBtn.addEventListener('click', clearBatchInputs);
realtimeSearchBtn.addEventListener('click', runRealtimeSearch);
batchSearchBtn.addEventListener('click', runBatchSearch);
realtimeSearchResetBtn.addEventListener('click', resetRealtimeSearch);
batchSearchResetBtn.addEventListener('click', resetBatchSearch);
realtimeEvalRunBtn.addEventListener('click', () => runEvaluation(
  currentRealtimeDocumentId,
  realtimeEvalQuestions,
  realtimeEvalResultPanel,
  log
));
batchEvalRunBtn.addEventListener('click', () => runEvaluation(
  currentBatchDocumentId,
  batchEvalQuestions,
  batchEvalResultPanel,
  batchLog
));
realtimeEvalResetBtn.addEventListener('click', () => resetEvaluation(realtimeEvalQuestions, realtimeEvalResultPanel));
batchEvalResetBtn.addEventListener('click', () => resetEvaluation(batchEvalQuestions, batchEvalResultPanel));
realtimeSearchInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    event.preventDefault();
    runRealtimeSearch();
  }
});
batchSearchInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    event.preventDefault();
    runBatchSearch();
  }
});
window.addEventListener('beforeunload', clearConversationMemoryOnUnload);

setWsStatus('disconnected', false);
setBatchLoading(false);
setRealtimeSummaryLoading(false);
renderSuggestedQuestionChips(realtimeQuestionList, [], () => {});
renderSuggestedQuestionChips(batchQuestionList, [], () => {});
resetRealtimeSearch();
resetBatchSearch();
resetEvaluation(realtimeEvalQuestions, realtimeEvalResultPanel);
resetEvaluation(batchEvalQuestions, batchEvalResultPanel);
switchView('realtime');
