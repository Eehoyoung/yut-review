"use client";

import { useEffect, useState } from "react";
import { OperatorFrame } from "@/features/admin/OperatorFrame";

export default function OperatorSecurityPage() {
  const [expiresAt, setExpiresAt] = useState("");

  useEffect(() => {
    setExpiresAt(sessionStorage.getItem("operatorExpiresAt") ?? "");
  }, []);

  return (
    <OperatorFrame title="세션 보안">
      <section className="panel stack">
        <div className="row">
          <h2>이메일 OTP 인증</h2>
          <span className="pill" data-tone="brand">필수</span>
        </div>
        <div className="list">
          <div className="list-item">
            <span className="lead">인증번호 유효시간</span>
            <span className="name">2분</span>
          </div>
          <div className="list-item">
            <span className="lead">기본 로그인 세션</span>
            <span className="name">10분</span>
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
          재배포합니다. 이미 발급된 세션은 늘어나지 않으므로 변경 후 다시 로그인해야 합니다.
        </p>
        <p className="hint">화면이나 API 요청으로 세션을 연장하는 기능은 제공하지 않습니다.</p>
      </section>
    </OperatorFrame>
  );
}
