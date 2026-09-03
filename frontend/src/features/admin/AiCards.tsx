"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { AI_FEATURE_LABEL, PLAN_LABEL } from "@/features/admin/labels";
import type { AiChatAnswer, AiEventCopy, AiFeature, AiImprovement, AiReportContent, AiStatus } from "@/types/api";

/**
 * 관리자 전용 AI 화면.
 *
 * 고객 경로(`/s/[storeToken]`)는 이 파일을 import하지 않는다. AI 공급자가 죽어도 손님이 윷을
 * 던지는 데 영향이 없어야 하고, 번들도 섞이면 안 된다.
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

/** AI 카드의 공통 껍데기. 제목, 남은 횟수, 실행 버튼, 오류 자리를 한 모양으로 맞춘다. */
function AiCard({
  title,
  lead,
  feature,
  status,
  actionLabel,
  pending,
  error,
  onRun,
  children,
}: {
  title: string;
  lead: string;
  feature: AiFeature;
  status?: AiStatus;
  actionLabel: string;
  pending: boolean;
  error: unknown;
  onRun: () => void;
  children?: React.ReactNode;
}) {
  const row = status?.features.find((f) => f.feature === feature);
  const blocked = !row?.allowed || row.remaining === 0;
  return (
    <section className="panel stack">
      <div className="row">
        <h2>{title}</h2>
        <AiUsageBadge status={status} feature={feature} />
      </div>
      <p className="lead">{lead}</p>
      {children}
      {error != null && (
        <p className="error" role="alert">
          {errorMessage(error)}
        </p>
      )}
      <button className="btn" onClick={onRun} disabled={pending || blocked}>
        {pending ? "만드는 중..." : actionLabel}
      </button>
      {!row?.allowed && <p className="hint">상위 요금제에서 사용할 수 있습니다.</p>}
      {row?.allowed && row.remaining === 0 && <p className="hint">이번 달 한도를 모두 썼습니다.</p>}
    </section>
  );
}

/** 이벤트 안내 문구. 실제 상품과 확률만 근거로 쓴다. */
export function AiEventCopyCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
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

  return (
    <AiCard
      title="이벤트 문구"
      lead="매장에 붙일 안내 문구를 만듭니다. 별점이나 좋은 리뷰를 조건으로 요구하는 문구는 만들지 않습니다."
      feature="AI_EVENT_COPY"
      status={status.data}
      actionLabel="문구 만들기"
      pending={run.isPending}
      error={run.error}
      onRun={() => run.mutate()}
    >
      <div className="field">
        <label htmlFor="ai-tone">말투</label>
        <input id="ai-tone" value={tone} maxLength={40} placeholder="담백하게" onChange={(e) => setTone(e.target.value)} />
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
      {run.data && (
        <div className="stack">
          <p className="result-copy">{run.data.headline}</p>
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
    </AiCard>
  );
}

/** 기간 리포트. 저장된 마지막 리포트를 먼저 보여 준다. */
export function AiReportCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const saved = useQuery({
    queryKey: ["ai-report", storeId],
    queryFn: () => api<{ content: AiReportContent; from: string; to: string }>(`/admin/stores/${storeId}/ai/report/latest`),
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

  return (
    <AiCard
      title="운영 리포트"
      lead="최근 참여와 쿠폰 집계를 근거로 무엇이 변했는지 정리합니다. 매출 데이터는 수집하지 않아 매출 변화는 말하지 않습니다."
      feature="AI_REPORT"
      status={status.data}
      actionLabel={report ? "다시 분석하기" : "리포트 만들기"}
      pending={run.isPending}
      error={run.error}
      onRun={() => run.mutate()}
    >
      {report && (
        <div className="stack">
          <h3>{report.title}</h3>
          <p className="lead">{report.summary}</p>
          {report.highlights.length > 0 && (
            <div className="list">
              {report.highlights.map((h) => (
                <div className="list-item" key={h.title}>
                  <span className="name">{h.title}</span>
                  <span className="lead">{h.evidence}</span>
                </div>
              ))}
            </div>
          )}
          {report.recommendations.length > 0 && (
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
          )}
          {report.dataLimitations.length > 0 && (
            <p className="notice">데이터 한계: {report.dataLimitations.join(" / ")}</p>
          )}
        </div>
      )}
    </AiCard>
  );
}

/** 개선 실험 제안. PRO 전용. */
export function AiImprovementCard({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const run = useMutation({
    mutationFn: () => api<AiImprovement>(`/admin/stores/${storeId}/ai/improvement`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ai-status", storeId] }),
  });

  return (
    <AiCard
      title="개선 제안"
      lead="지금 데이터에서 바꿔 볼 만한 것을 작은 실험으로 제안합니다. 한 실험에서 한 가지만 바꿉니다."
      feature="AI_IMPROVEMENT"
      status={status.data}
      actionLabel="실험 제안받기"
      pending={run.isPending}
      error={run.error}
      onRun={() => run.mutate()}
    >
      {run.data && (
        <div className="stack">
          {run.data.experiments.map((e) => (
            <div className="prize-block" key={e.name}>
              <div className="row">
                <h3>{e.name}</h3>
                <span className="pill">{e.durationDays}일</span>
              </div>
              <p className="lead">{e.change}</p>
              <p className="hint">
                바꾸는 것: {e.variableChanged} · 확인 방법: {e.howToMeasure}
              </p>
            </div>
          ))}
          {run.data.hypotheses.length > 0 && <p className="notice">가설: {run.data.hypotheses.join(" / ")}</p>}
        </div>
      )}
    </AiCard>
  );
}

/** AI 매니저 대화. 서버가 매장을 고정하므로 다른 매장 데이터는 물어도 나오지 않는다. */
export function AiManagerChat({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const status = useAiStatus(storeId);
  const [message, setMessage] = useState("");
  const [turns, setTurns] = useState<{ role: "user" | "assistant"; content: string }[]>([]);
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

  const row = status.data?.features.find((f) => f.feature === "AI_CHAT");
  const blocked = !row?.allowed || row.remaining === 0;

  return (
    <section className="panel stack">
      <div className="row">
        <h2>{AI_FEATURE_LABEL.AI_CHAT}</h2>
        <AiUsageBadge status={status.data} feature="AI_CHAT" />
      </div>
      <p className="lead">이 매장의 운영 데이터만 보고 답합니다. 고객 개인정보는 묻거나 보여줄 수 없습니다.</p>

      {turns.length > 0 && (
        <div className="chat-log">
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
            {errorMessage(ask.error)}
          </p>
        )}
        <button className="btn" disabled={ask.isPending || blocked || !message.trim()}>
          {ask.isPending ? "생각하는 중..." : "물어보기"}
        </button>
        {!row?.allowed && <p className="hint">프로 요금제에서 사용할 수 있습니다.</p>}
      </form>
    </section>
  );
}
