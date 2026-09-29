"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { api, errorMessage } from "@/lib/api";
import type { OperatorSessionPolicy } from "@/types/api";

/** 초를 "2분", "1분 30초"처럼 읽히게. */
function duration(seconds: number) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m === 0 ? `${s}초` : s === 0 ? `${m}분` : `${m}분 ${s}초`;
}

export default function OperatorSecurityPage() {
  const [expiresAt, setExpiresAt] = useState("");
  // 설정값을 화면에 적어 두지 않는다. 환경변수를 바꾸면 이 화면도 같이 바뀌어야 한다.
  const policy = useQuery({
    queryKey: ["operator-session-policy"],
    queryFn: () => api<OperatorSessionPolicy>("/admin/operator/session-policy"),
  });

  useEffect(() => {
    setExpiresAt(sessionStorage.getItem("operatorExpiresAt") ?? "");
  }, []);

  const show = (pick: (p: OperatorSessionPolicy) => number) => (policy.data ? duration(pick(policy.data)) : "확인 중");

  return (
    <OperatorFrame title="세션 보안">
      <section className="panel stack">
        <div className="row">
          <h2>이메일 OTP 인증</h2>
          <span className="pill" data-tone="brand">필수</span>
        </div>
        {policy.isError && (
          <p className="error" role="alert">
            {errorMessage(policy.error)}
          </p>
        )}
        <div className="list">
          <div className="list-item">
            <span className="lead">인증번호 유효시간</span>
            <span className="name">{show((p) => p.otpTtlSeconds)}</span>
          </div>
          <div className="list-item">
            <span className="lead">로그인 세션</span>
            <span className="name">{show((p) => p.sessionTtlSeconds)}</span>
          </div>
          <div className="list-item">
            <span className="lead">자동 연장</span>
            <span className="name">사용 안 함</span>
          </div>
          <div className="list-item">
            <span className="lead">현재 세션 만료</span>
            <span className="name">{expiresAt ? new Date(expiresAt).toLocaleString("ko-KR") : "확인 중"}</span>
          </div>
        </div>
        <p className="notice">
          활동 중이어도 만료 시각은 바뀌지 않습니다. 만료되면 이메일 인증부터 다시 진행합니다.
        </p>
      </section>

      <section className="panel stack">
        <h2>대규모 작업</h2>
        <p className="lead">
          긴 작업이 예정된 경우에만 서버 환경변수 <code>OPERATOR_SESSION_TTL_SECONDS</code>를 변경하고
          재배포합니다. 이미 발급된 세션은 늘어나지 않으므로 변경 후 다시 로그인해야 합니다. 작업이 끝나면 600으로
          되돌립니다.
        </p>
        <p className="hint">화면이나 API 요청으로 세션을 연장하는 기능은 제공하지 않습니다.</p>
      </section>
    </OperatorFrame>
  );
}
