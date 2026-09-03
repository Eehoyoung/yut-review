"use client";
import { FormEvent, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, api, errorMessage } from "@/lib/api";
import { ADMIN_ERROR_HINT, PLAN_LABEL } from "@/features/admin/labels";
import type { AiChatAnswer, AiEventCopy, AiFeature, AiImprovement, AiReportContent, AiStatus } from "@/types/api";

/**
 * 관리자 전용 AI 화면.
 *
 * 고객 경로(`/s/[storeToken]`)는 이 파일을 import하지 않는다. AI 공급자가 죽어도 손님이 윷을
 * 던지는 데 영향이 없어야 하고, 번들도 섞이면 안 된다.
 *
 * 네 기능을 같은 카드로 반복하지 않는다. 성격이 다르기 때문이다. 리포트는 읽는 것, 개선 제안은
 * 실행 목록, 문구는 입력을 받아 만드는 것, 매니저는 대화다. 같은 껍데기에 넣으면 무엇이 무엇인지
 * 구분되지 않고, 화면이 길어질수록 사장은 전부 같은 것으로 본다.
 */

/** 남은 횟수. 왜 못 쓰는지가 보이지 않으면 사장은 고장으로 읽는다. */
export function AiUsageBadge({ status, feature }: { status?: AiStatus; feature: AiFeature }) {
  const row = status?.features.find((f) => f.feature === feature);
  if (!row) return null;
  if (!row.allowed)
    return (
      <span className="pill" data-tone="off">
        {PLAN_LABEL[status!.plan]} 미포함
      </span>
    );
  return (
    <span className="pill" data-tone={row.remaining === 0 ? "off" : "wood"}>
      이번 달 {row.used}/{row.limitPerMonth}
    </span>
  );
}

function useAiStatus(storeId: string) {
  return useQuery({ queryKey: ["ai-status", storeId], queryFn: () => api<AiStatus>(`/admin/stores/${storeId}/ai/status`) });
}

/** 관리자 전용 코드는 여기서 풀고, 나머지는 서버 메시지를 그대로 쓴다. */
function adminError(error: unknown) {
  const hint = error instanceof ApiClientError ? ADMIN_ERROR_HINT[error.code] : undefined;
  return hint ?? errorMessage(error);
}

function blocked(status: AiStatus | undefined, feature: AiFeature) {
  const row = status?.features.find((f) => f.feature === feature);
  if (!row?.allowed) return "상위 요금제에서 사용할 수 있습니다.";
  if (row.remaining === 0) return "이번 달 한도를 모두 썼습니다.";
  return "";
}

/**
 * 화면의 첫 줄. 지금 이 매장에서 AI가 무엇을 알아냈는지 한 문장으로 보여 준다.
 * 아무것도 없으면 그 자리에서 만들 수 있게 한다. 빈 화면에 "없습니다"만 두지 않는다.
 */
export function AiInsightCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const saved = useQuery({
    queryKey: ["ai-report", storeId],
    queryFn: () =>
      api<{ content: AiReportContent; from: string; to: string; createdAt: string }>(
        `/admin/stores/${storeId}/ai/report/latest`,
      ),
    retry: false,
  });
  const run = useMutation({
    mutationFn: () => api<AiReportContent>(`/admin/stores/${storeId}/ai/report`, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["ai-status", storeId] });
      qc.invalidateQueries({ queryKey: ["ai-report", storeId] });
    },
  });
  const why = blocked(status.data, "AI_REPORT");

  if (saved.data)
    return (
      <section className="insight">
        <p className="insight-when">
          {saved.data.from} ~ {saved.data.to}
        </p>
        <h2 className="insight-title">{saved.data.content.title}</h2>
        <p className="insight-summary">{saved.data.content.summary}</p>
      </section>
    );

  return (
    <section className="panel stack">
      <div className="row">
        <h2>아직 분석한 결과가 없어요</h2>
        <AiUsageBadge status={status.data} feature="AI_REPORT" />
      </div>
      <p className="lead">
        최근 참여와 쿠폰 집계를 근거로 무엇이 변했는지 정리합니다. 매출 데이터는 수집하지 않아 매출
        변화는 말하지 않습니다.
      </p>
      {run.isError && (
        <p className="error" role="alert">
          {adminError(run.error)}
        </p>
      )}
      <button className="btn" onClick={() => run.mutate()} disabled={run.isPending || why !== ""}>
        {run.isPending ? "분석하는 중..." : "리포트 만들기"}
      </button>
      {why && <p className="hint">{why}</p>}
    </section>
  );
}

/** 리포트 본문. 읽는 화면이라 카드로 감싸지 않고 문서처럼 펼친다. */
export function AiReportCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const saved = useQuery({
    queryKey: ["ai-report", storeId],
    queryFn: () => api<{ content: AiReportContent }>(`/admin/stores/${storeId}/ai/report/latest`),
    retry: false,
  });
  const run = useMutation({
    mutationFn: () => api<AiReportContent>(`/admin/stores/${storeId}/ai/report`, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["ai-status", storeId] });
      qc.invalidateQueries({ queryKey: ["ai-report", storeId] });
    },
  });
  const report = run.data ?? saved.data?.content;
  const why = blocked(status.data, "AI_REPORT");
  if (!report) return null;

  return (
    <section className="panel stack">
      <div className="row">
        <h2>무엇이 변했나</h2>
        <AiUsageBadge status={status.data} feature="AI_REPORT" />
      </div>

      {report.highlights.length > 0 && (
        <div className="list">
          {report.highlights.map((h) => (
            <div className="list-item finding" key={h.title}>
              <span className="name">{h.title}</span>
              <span className="lead">{h.evidence}</span>
            </div>
          ))}
        </div>
      )}

      {report.concerns.length > 0 && (
        <>
          <hr className="hair" />
          <h3>살펴볼 것</h3>
          <div className="list">
            {report.concerns.map((c) => (
              <div className="list-item finding" key={c.title}>
                <span className="name">{c.title}</span>
                <span className="lead">{c.evidence}</span>
              </div>
            ))}
          </div>
        </>
      )}

      {report.recommendations.length > 0 && (
        <>
          <hr className="hair" />
          <h3>해볼 것</h3>
          <ol className="howto">
            {report.recommendations.map((r, i) => (
              <li key={r.action}>
                <span className="howto-n" aria-hidden="true">
                  {i + 1}
                </span>
                <span>
                  {r.action}
                  <br />
                  <small className="hint">
                    {r.reason} · 확인 지표: {r.successMetric}
                  </small>
                </span>
              </li>
            ))}
          </ol>
        </>
      )}

      {report.dataLimitations.length > 0 && (
        <p className="notice">데이터 한계: {report.dataLimitations.join(" / ")}</p>
      )}

      {run.isError && (
        <p className="error" role="alert">
          {adminError(run.error)}
        </p>
      )}
      <button className="btn secondary" onClick={() => run.mutate()} disabled={run.isPending || why !== ""}>
        {run.isPending ? "분석하는 중..." : "다시 분석하기"}
      </button>
      {why && <p className="hint">{why}</p>}
    </section>
  );
}

/** 개선 제안. 실행 목록이라 실험 하나하나를 블록으로 세운다. */
export function AiImprovementCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const run = useMutation({
    mutationFn: () => api<AiImprovement>(`/admin/stores/${storeId}/ai/improvement`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ai-status", storeId] }),
  });
  const why = blocked(status.data, "AI_IMPROVEMENT");

  return (
    <section className="panel stack">
      <div className="row">
        <h2>바꿔볼 만한 것</h2>
        <AiUsageBadge status={status.data} feature="AI_IMPROVEMENT" />
      </div>
      <p className="lead">한 실험에서 한 가지만 바꿉니다. 무엇이 효과였는지 알 수 있어야 하니까요.</p>

      {run.data?.experiments.map((e, i) => (
        <div className="experiment" key={e.name}>
          <div className="row">
            <h3>
              <span className="experiment-n" aria-hidden="true">
                {i + 1}
              </span>
              {e.name}
            </h3>
            <span className="pill">{e.durationDays}일</span>
          </div>
          <p className="lead">{e.change}</p>
          <div className="list">
            <div className="list-item">
              <span className="lead">바꾸는 것</span>
              <span className="name">{e.variableChanged}</span>
            </div>
            <div className="list-item">
              <span className="lead">확인 방법</span>
              <span className="name">{e.howToMeasure}</span>
            </div>
          </div>
        </div>
      ))}

      {run.isError && (
        <p className="error" role="alert">
          {adminError(run.error)}
        </p>
      )}
      <button className="btn secondary" onClick={() => run.mutate()} disabled={run.isPending || why !== ""}>
        {run.isPending ? "찾는 중..." : run.data ? "다시 제안받기" : "실험 제안받기"}
      </button>
      {why && <p className="hint">{why}</p>}
    </section>
  );
}

/**
 * 이벤트 문구는 입력을 받아 만드는 것이라 바텀시트로 연다. 읽는 화면들 사이에 입력 폼을 끼워 두면
 * 무엇이 결과이고 무엇이 조작인지 흐려진다. 쿠폰 사용 처리와 같은 시트를 쓴다.
 */
export function AiEventCopyDialog({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const [open, setOpen] = useState(false);
  const [tone, setTone] = useState("");
  const [extra, setExtra] = useState("");
  const run = useMutation({
    mutationFn: () =>
      api<AiEventCopy>(`/admin/stores/${storeId}/ai/event-copy`, {
        method: "POST",
        body: JSON.stringify({ tone, additionalRequest: extra }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ai-status", storeId] }),
  });
  const why = blocked(status.data, "AI_EVENT_COPY");

  return (
    <section className="panel stack">
      <div className="row">
        <h2>안내 문구 만들기</h2>
        <AiUsageBadge status={status.data} feature="AI_EVENT_COPY" />
      </div>
      <p className="lead">
        매장에 붙일 문구를 만듭니다. 별점이나 좋은 리뷰를 조건으로 요구하는 문구는 만들지 않습니다.
      </p>

      {run.data && (
        <div className="copyout">
          <p className="copyout-headline">{run.data.headline}</p>
          <p className="lead">{run.data.subheadline}</p>
          <div className="list">
            {run.data.posterLines.map((line) => (
              <div className="list-item" key={line}>
                <span className="name">{line}</span>
              </div>
            ))}
          </div>
          <p className="notice">{run.data.policyNotice}</p>
          <p className="hint">직원 안내: {run.data.staffGuide}</p>
        </div>
      )}

      <button className="btn secondary" onClick={() => setOpen(true)} disabled={why !== ""}>
        {run.data ? "다시 만들기" : "문구 만들기"}
      </button>
      {why && <p className="hint">{why}</p>}

      {open && (
        <div className="dialog-backdrop" role="presentation">
          <form
            className="dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="copy-title"
            onSubmit={(e: FormEvent) => {
              e.preventDefault();
              run.mutate(undefined, { onSuccess: () => setOpen(false) });
            }}
          >
            <h2 id="copy-title">어떤 문구가 필요하세요?</h2>
            <div className="field">
              <label htmlFor="ai-tone">말투</label>
              <input
                id="ai-tone"
                value={tone}
                maxLength={40}
                placeholder="담백하게"
                onChange={(e) => setTone(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="ai-extra">덧붙일 요청</label>
              <textarea
                id="ai-extra"
                value={extra}
                maxLength={300}
                placeholder="가족 손님이 많아요"
                onChange={(e) => setExtra(e.target.value)}
              />
            </div>
            {run.isError && (
              <p className="error" role="alert">
                {adminError(run.error)}
              </p>
            )}
            <div className="sheet-actions">
              <button type="button" className="btn ghost" onClick={() => setOpen(false)}>
                취소
              </button>
              <button className="btn" disabled={run.isPending}>
                {run.isPending ? "만드는 중..." : "만들기"}
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  );
}

/** AI 매니저 대화. 서버가 매장을 고정하므로 다른 매장 데이터는 물어도 나오지 않는다. */
export function AiManagerChat({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const [message, setMessage] = useState("");
  const [turns, setTurns] = useState<{ role: "user" | "assistant"; content: string }[]>([]);
  const logRef = useRef<HTMLDivElement>(null);
  const ask = useMutation({
    mutationFn: (question: string) =>
      api<AiChatAnswer>(`/admin/stores/${storeId}/ai/chat`, {
        method: "POST",
        body: JSON.stringify({ message: question, history: turns.slice(-6) }),
      }),
    onSuccess: (data, question) => {
      setTurns((prev) => [...prev, { role: "user", content: question }, { role: "assistant", content: data.answer }]);
      setMessage("");
      qc.invalidateQueries({ queryKey: ["ai-status", storeId] });
    },
  });

  // 답이 오면 마지막 줄로 내린다. 위쪽을 보고 있으면 새 답이 온 줄도 모른다.
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [turns]);

  const why = blocked(status.data, "AI_CHAT");

  return (
    <section className="panel stack">
      <div className="row">
        <h2>물어보기</h2>
        <AiUsageBadge status={status.data} feature="AI_CHAT" />
      </div>
      <p className="lead">이 매장의 운영 데이터만 보고 답합니다. 고객 개인정보는 묻거나 보여줄 수 없습니다.</p>

      {turns.length > 0 && (
        <div className="chat-log" ref={logRef} aria-live="polite">
          {turns.map((t, i) => (
            <p key={i} className={t.role === "user" ? "chat-turn is-me" : "chat-turn"}>
              {t.content}
            </p>
          ))}
        </div>
      )}

      <form
        className="stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (message.trim()) ask.mutate(message.trim());
        }}
      >
        <div className="field">
          <label htmlFor="ai-chat">질문</label>
          <textarea
            id="ai-chat"
            value={message}
            maxLength={800}
            placeholder="지난주보다 참여가 늘었나요?"
            onChange={(e) => setMessage(e.target.value)}
          />
        </div>
        {ask.isError && (
          <p className="error" role="alert">
            {adminError(ask.error)}
          </p>
        )}
        <button className="btn" disabled={ask.isPending || why !== "" || !message.trim()}>
          {ask.isPending ? "생각하는 중..." : "물어보기"}
        </button>
        {why && <p className="hint">{why}</p>}
      </form>
    </section>
  );
}
