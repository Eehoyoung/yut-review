"use client";
import { useParams } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { formatBusinessNumber, formatTel, isStorePhone, onlyDigits, STORE_PHONE_MAX } from "@/features/normalize";
import { api, errorMessage } from "@/lib/api";

type PosterBrandTheme = "SODAM" | "FOREST" | "PLUM";
type StoreDetail = {
  id: number;
  name: string;
  phone: string;
  address: string;
  businessNumber: string;
  representativeName: string;
  openingDate: string;
  naverPlaceUrl?: string;
  posterTagline?: string;
  posterBrandTheme?: PosterBrandTheme;
  brandingAvailable?: boolean;
};

const BRAND_THEMES: { value: PosterBrandTheme; label: string; description: string }[] = [
  { value: "SODAM", label: "소담 네이비", description: "차분한 네이비와 오렌지" },
  { value: "FOREST", label: "포레스트", description: "깊은 초록과 부드러운 민트" },
  { value: "PLUM", label: "플럼", description: "짙은 자주와 연한 분홍" },
];

const openingDateLabel = (v: string) => (v.length === 8 ? `${v.slice(0, 4)}년 ${Number(v.slice(4, 6))}월 ${Number(v.slice(6))}일` : "");

/**
 * 매장 정보. 주소·전화·네이버 링크·(PRO) 안내물 문구는 바꾸고, 매장명·사업자등록번호·대표자·개업일자는 보여 주기만 한다.
 * 매장명은 인쇄해 보낸 안내물·스티커에 찍혀 있고, 나머지는 국세청 확인과 계정 찾기의 본인 확인 값이라 서버도 막는다.
 * 주소는 입점 키트(안내물·스티커 실물)를 받는 곳이다. 가입 때 받지 않으므로 여기서 채운다.
 */
export default function StoreSettings() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");
  const [url, setUrl] = useState("");
  const [tagline, setTagline] = useState("");
  const [brandTheme, setBrandTheme] = useState<PosterBrandTheme>("SODAM");
  const [tried, setTried] = useState(false);

  const store = useQuery({ queryKey: ["store", id], queryFn: () => api<StoreDetail>(`/admin/stores/${id}`) });

  useEffect(() => {
    if (!store.data) return;
    setPhone(store.data.phone ?? "");
    setAddress(store.data.address ?? "");
    setUrl(store.data.naverPlaceUrl ?? "");
    setTagline(store.data.posterTagline ?? "");
    setBrandTheme(store.data.posterBrandTheme ?? "SODAM");
  }, [store.data]);

  const save = useMutation({
    mutationFn: () =>
      api<StoreDetail>(`/admin/stores/${id}`, {
        method: "PUT",
        body: JSON.stringify({
          phone,
          address: address.trim(),
          naverPlaceUrl: url.trim(),
          posterTagline: store.data?.brandingAvailable ? tagline.trim() : undefined,
          posterBrandTheme: store.data?.brandingAvailable ? brandTheme : undefined,
        }),
      }),
    onSuccess: (data) => qc.setQueryData(["store", id], data),
  });

  const phoneProblem = !isStorePhone(phone) ? "매장 전화번호를 확인해 주세요. 예: 02-123-4567, 010-1234-5678, 1588-1234" : "";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setTried(true);
    if (phoneProblem) {
      document.getElementById("store-phone")?.focus();
      return;
    }
    save.mutate();
  };

  const s = store.data;

  return (
    <AdminFrame title="매장 정보">
      {store.isError && (
        <p className="error" role="alert">
          {errorMessage(store.error)}
        </p>
      )}
      {store.isPending && (
        <div aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 정보를 불러오는 중</span>
          <div className="skeleton" style={{ height: 180 }} />
        </div>
      )}

      {s && (
        <>
          <section className="panel stack" aria-labelledby="fixed-info">
            <div className="owner-section-heading">
              <h2 id="fixed-info">사업자 정보</h2>
              <p className="hint">가입 때 확인한 정보라 여기서 바꿀 수 없어요. 상호나 대표자가 바뀌었다면 소담랩스에 문의해 주세요.</p>
            </div>
            <div className="list">
              <div className="list-item">
                <span className="lead">매장명</span>
                <span className="name">{s.name}</span>
              </div>
              <div className="list-item">
                <span className="lead">사업자등록번호</span>
                <span className="name">{formatBusinessNumber(s.businessNumber) || "-"}</span>
              </div>
              <div className="list-item">
                <span className="lead">대표자</span>
                <span className="name">{s.representativeName || "-"}</span>
              </div>
              <div className="list-item">
                <span className="lead">개업일자</span>
                <span className="name">{openingDateLabel(s.openingDate) || "-"}</span>
              </div>
            </div>
          </section>

          <form className="panel stack owner-settings-panel" onSubmit={submit} noValidate>
            <div className="owner-section-heading">
              <h2>연락처와 주소</h2>
              <p className="hint">손님 화면과 입점 키트 배송에 쓰는 정보예요.</p>
            </div>
            <div className="field">
              <label htmlFor="store-address">매장 주소</label>
              <input
                id="store-address"
                value={address}
                maxLength={255}
                autoComplete="street-address"
                onChange={(event) => setAddress(event.target.value)}
                placeholder="예: 서울시 강남구 테헤란로 1, 2층"
              />
              <small className="hint">
                {address.trim()
                  ? "입점 키트(안내물·테이블 스티커)를 이 주소로 보내 드려요. 층·호수까지 적어 주세요."
                  : "주소를 적어 주셔야 입점 키트(안내물·테이블 스티커)를 보내 드릴 수 있어요."}
              </small>
            </div>
            <div className="field">
              <label htmlFor="store-phone">매장 전화</label>
              <input
                id="store-phone"
                inputMode="tel"
                autoComplete="tel"
                value={formatTel(phone)}
                onChange={(event) => setPhone(onlyDigits(event.target.value, STORE_PHONE_MAX))}
                aria-invalid={tried && phoneProblem ? true : undefined}
                aria-describedby="store-phone-hint"
              />
              <small id="store-phone-hint" className={tried && phoneProblem ? "error" : "hint"}>
                {tried && phoneProblem ? phoneProblem : "가게 유선전화도 괜찮아요."}
              </small>
            </div>
            <div className="field">
              <label htmlFor="naver-url">네이버 매장 링크 (선택)</label>
              <input
                id="naver-url"
                type="url"
                value={url}
                onChange={(event) => setUrl(event.target.value)}
                placeholder="https://map.naver.com/..."
              />
              <small className="hint">등록하면 고객 화면에 선택 링크가 표시됩니다.</small>
            </div>
            <fieldset className="branding-fieldset" disabled={!s.brandingAvailable}>
              <legend>PRO 안내물 브랜딩</legend>
              <p className="hint">
                {s.brandingAvailable
                  ? "선택한 팔레트와 문구는 안내물·스티커·인쇄용 PDF에 적용됩니다."
                  : "PRO 요금제에서 매장 팔레트와 안내 문구를 설정할 수 있습니다."}
              </p>
              <div className="brand-theme-grid" role="radiogroup" aria-label="안내물 팔레트">
                {BRAND_THEMES.map((theme) => (
                  <label key={theme.value} className="brand-theme-option" data-theme={theme.value}>
                    <input
                      type="radio"
                      name="posterBrandTheme"
                      value={theme.value}
                      checked={brandTheme === theme.value}
                      onChange={() => setBrandTheme(theme.value)}
                    />
                    <span className="brand-theme-swatch" aria-hidden="true" />
                    <span>
                      <b>{theme.label}</b>
                      <small>{theme.description}</small>
                    </span>
                  </label>
                ))}
              </div>
              <div className="field">
                <label htmlFor="poster-tagline">안내물 한 줄 문구</label>
                <input
                  id="poster-tagline"
                  value={tagline}
                  onChange={(event) => setTagline(event.target.value)}
                  maxLength={60}
                  placeholder="예: 오늘도 찾아주셔서 고맙습니다"
                />
                <small className="hint">비워 두면 안내물 종류별 기본 문구를 사용합니다. {tagline.length}/60</small>
              </div>
            </fieldset>
            {save.isError && (
              <p className="error" role="alert">
                {errorMessage(save.error)}
              </p>
            )}
            {save.isSuccess && (
              <p className="success" role="status">
                매장 정보를 저장했어요.
              </p>
            )}
            <button className="btn" disabled={save.isPending}>
              {save.isPending ? "저장 중" : "매장 정보 저장"}
            </button>
          </form>
        </>
      )}
    </AdminFrame>
  );
}
