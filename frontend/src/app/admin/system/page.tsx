"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { opsApi } from "@/features/system/api";
import { PLAN_NAME, consoleError, count } from "@/features/system/labels";
import type { PlatformOverview } from "@/types/api";

/** 플랫폼 전체가 어떻게 돌아가고 있는지 한 화면. 매장 단위 집계까지만 본다(손님 개인정보는 없다). */
export default function Overview() {
  const q = useQuery({ queryKey: ["ops-overview"], queryFn: () => opsApi<PlatformOverview>("/overview"), retry: false });

  if (q.isPending)
    return (
      <SystemFrame title="개요">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">현황을 불러오는 중</span>
          <div className="skeleton" style={{ height: 96 }} />
          <div className="skeleton" style={{ height: 180 }} />
        </div>
      </SystemFrame>
    );

  if (q.isError)
    return (
      <SystemFrame title="개요">
        <p className="error" role="alert">
          {consoleError(q.error)}
        </p>
      </SystemFrame>
    );

  const d = q.data;
  return (
    <SystemFrame title="개요">
      <dl className="stats">
        {[
          ["운영 중 매장", d.stores.active],
          ["중지된 매장", d.stores.inactive],
          ["오늘 참여", d.plays.today],
          ["전체 참여", d.plays.total],
        ].map(([label, value]) => (
          <div key={String(label)}>
            <dt>{label}</dt>
            <dd>{count(Number(value))}</dd>
          </div>
        ))}
      </dl>

      <section className="panel stack">
        <h2>쿠폰</h2>
        <div className="list">
          <div className="list-item">
            <span className="lead">미사용</span>
            <span className="name">{count(d.coupons.issued)}장</span>
          </div>
          <div className="list-item">
            <span className="lead">사용 완료</span>
            <span className="name">{count(d.coupons.redeemed)}장</span>
          </div>
        </div>
      </section>

      <section className="panel stack">
        <h2>요금제</h2>
        <div className="list">
          {Object.entries(d.plans).map(([plan, stores]) => (
            <div className="list-item" key={plan}>
              <span className="lead">{PLAN_NAME[plan] ?? plan}</span>
              <span className="name">{count(stores)}곳</span>
            </div>
          ))}
        </div>
        <Link className="btn secondary" href="/admin/system/stores">
          매장별로 보기
        </Link>
      </section>

      <section className="panel stack">
        <h2>이번 달 AI 호출</h2>
        <div className="list">
          <div className="list-item">
            <span className="lead">전체</span>
            <span className="name">{count(d.ai.monthCalls)}회</span>
          </div>
          <div className="list-item">
            <span className="lead">실패</span>
            <span className="name">{count(d.ai.monthFailures)}회</span>
          </div>
        </div>
        <p className="hint">실패한 호출은 한도를 되돌려 준다. 손님 흐름은 AI 상태와 무관하게 돌아간다.</p>
      </section>

      <section className="panel stack">
        <h2>접근 보호</h2>
        {/* 지금 당장 손봐야 하는 것부터 위에 둔다. 숫자가 0이 아닌 줄이 곧 할 일이다. */}
        {d.security.operatorsWithoutTotp > 0 && (
          <p className="notice" role="status">
            2단계 인증을 아직 등록하지 않은 운영자가 {count(d.security.operatorsWithoutTotp)}명 있습니다.
          </p>
        )}
        {d.security.failedLogins24h > 0 && (
          <p className="notice" role="status">
            최근 24시간 실패한 시도 {count(d.security.failedLogins24h)}건. 접근 기록에서 확인하세요.
          </p>
        )}
        <div className="list">
          <div className="list-item">
            <span className="lead">2단계 인증</span>
            <span className="pill" data-tone="ok">항상 필요</span>
          </div>
          <div className="list-item">
            <span className="lead">IP 제한</span>
            <span className="pill" data-tone={d.security.ipRestricted ? "ok" : "off"}>
              {d.security.ipRestricted ? "적용 중" : "미설정"}
            </span>
          </div>
          <div className="list-item">
            <span className="lead">세션 / 자리 비움</span>
            <span className="name">
              {d.security.sessionMinutes}분 / {d.security.idleMinutes}분
            </span>
          </div>
          <div className="list-item">
            <span className="lead">지금 열려 있는 세션</span>
            <span className="name">{count(d.security.activeSessions)}개</span>
          </div>
          <div className="list-item">
            <span className="lead">운영자 계정</span>
            <span className="name">
              {count(d.security.operators)}개{d.security.lockedOperators > 0 && ` · 잠김 ${count(d.security.lockedOperators)}`}
            </span>
          </div>
        </div>
        <Link className="btn secondary" href="/admin/system/audit">
          접근 기록 보기
        </Link>
        <Link className="btn secondary" href="/admin/system/security">
          내 계정 보안
        </Link>
      </section>
    </SystemFrame>
  );
}
