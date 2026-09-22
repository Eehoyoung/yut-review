import { api } from "@/lib/api";

/**
 * 운영자 기기 인증(WebAuthn).
 *
 * 서버가 CBOR을 파싱하지 않는 이유가 여기 있다. 등록 응답의 `getPublicKey()`가 공개키를
 * SPKI(X.509) DER로 바로 주고, Java `KeyFactory`가 그 형식을 그대로 읽는다. 그래서 백엔드에
 * WebAuthn 라이브러리를 하나도 넣지 않았다.
 *
 * `getPublicKey()`는 Chrome 85+, Safari 16+, Firefox 119+에 있다. 없는 브라우저에서는
 * 등록을 시작하지 않고 문장으로 알린다 — CBOR 파서를 넣는 것보다 "다른 브라우저를 쓰세요"가
 * 싸고, 운영자는 한 명에서 몇 명 규모다.
 */

const b64url = (buffer: ArrayBuffer) => {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

const b64 = (buffer: ArrayBuffer) => {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
};

const fromB64url = (value: string) => {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
};

export function webauthnAvailable() {
  return typeof window !== "undefined" && !!window.PublicKeyCredential && !!navigator.credentials;
}

type RegisterChallenge = { challenge: string; rpId: string; timeoutMs: number };
type AuthChallenge = RegisterChallenge & { allowCredentials: string[] };

/**
 * 기기 등록. 화면에서 이 함수를 부르기 전에 `webauthnAvailable()`을 확인한다.
 *
 * `residentKey: "preferred"`로 두는 것은 휴대폰이 패스키로 저장해 두고 나중에 아이디 없이
 * 꺼내 쓸 수 있게 하려는 것이다. 강제하지 않는 이유는 오래된 보안키가 저장 공간이 모자라
 * 등록 자체를 거부하기 때문이다.
 */
export async function registerDevice(name: string) {
  const { challenge, rpId, timeoutMs } = await api<RegisterChallenge>(
    "/admin/operator/access/register-challenge",
    { method: "POST" },
  );

  const credential = (await navigator.credentials.create({
    publicKey: {
      challenge: fromB64url(challenge),
      rp: { id: rpId, name: "소담한판 운영자" },
      // userHandle은 서버가 쓰지 않는다. 신원은 이미 JWT가 말하고, 여기에 계정 식별자를
      // 넣으면 인증기에 그 값이 남는다.
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name, displayName: name },
      pubKeyCredParams: [
        { type: "public-key", alg: -7 }, // ES256
        { type: "public-key", alg: -257 }, // RS256
      ],
      authenticatorSelection: { userVerification: "preferred", residentKey: "preferred" },
      // attestation을 받지 않는다. 서버가 검증하지 않을 값을 굳이 받으면 제조사 식별자만 남는다.
      attestation: "none",
      timeout: timeoutMs,
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("등록이 취소됐습니다.");

  const response = credential.response as AuthenticatorAttestationResponse;
  if (typeof response.getPublicKey !== "function") {
    throw new Error("이 브라우저는 기기 등록을 지원하지 않습니다. Chrome, Safari, Firefox 최신 버전을 사용해 주세요.");
  }
  const publicKey = response.getPublicKey();
  if (!publicKey) throw new Error("기기 공개키를 읽지 못했습니다.");

  return api<{ id: number; name: string; createdAt: string; deviceCount: number }>(
    "/admin/operator/access/register",
    {
      method: "POST",
      body: JSON.stringify({
        name,
        credentialId: b64url(credential.rawId),
        publicKey: b64(publicKey),
        algorithm: response.getPublicKeyAlgorithm(),
        clientDataJson: new TextDecoder().decode(response.clientDataJSON),
      }),
    },
  );
}

/**
 * 기기 인증. 성공하면 통행증을 sessionStorage에 넣는다.
 *
 * localStorage가 아닌 이유: 통행증은 4시간짜리이고 브라우저를 닫으면 사라지는 편이 맞다.
 * JWT와 같은 자리에 같은 수명 정책으로 둬야 한쪽만 남아 헷갈리는 상태가 없다.
 */
export async function authenticateDevice() {
  const { challenge, rpId, allowCredentials, timeoutMs } = await api<AuthChallenge>(
    "/admin/operator/access/authenticate-challenge",
    { method: "POST" },
  );
  if (allowCredentials.length === 0) throw new Error("등록된 기기가 없습니다.");

  const credential = (await navigator.credentials.get({
    publicKey: {
      challenge: fromB64url(challenge),
      rpId,
      allowCredentials: allowCredentials.map((id) => ({ type: "public-key" as const, id: fromB64url(id) })),
      userVerification: "preferred",
      timeout: timeoutMs,
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("인증이 취소됐습니다.");

  const response = credential.response as AuthenticatorAssertionResponse;
  const result = await api<{ deviceToken: string; expiresAt: string; deviceName: string }>(
    "/admin/operator/access/authenticate",
    {
      method: "POST",
      body: JSON.stringify({
        credentialId: b64url(credential.rawId),
        clientDataJson: new TextDecoder().decode(response.clientDataJSON),
        authenticatorData: b64url(response.authenticatorData),
        signature: b64url(response.signature),
      }),
    },
  );
  sessionStorage.setItem("operatorDeviceToken", result.deviceToken);
  return result;
}
