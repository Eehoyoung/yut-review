/**
 * 입력 정규화. 서버가 어차피 다시 검증하지만, 손님과 사장이 애초에 틀린 값을 넣을 수 없게
 * 화면에서 먼저 막는다. 잘라내기(slice)까지 하는 이유는 maxLength가 붙여넣기와
 * 일부 안드로이드 키보드에서 새는 경우가 있어서다.
 */
/** 한글 IME가 전각 숫자(０１２)를 내보내면 그냥 지워져서 안 눌린 것처럼 보인다. 먼저 반각으로 맞춘다. */
const toHalfWidth = (value: string) => value.replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));

export const onlyDigits = (value: string, max: number) => toHalfWidth(value).replace(/\D/g, "").slice(0, max);

/**
 * 소수 입력. type="number"는 하이픈(-5)과 지수(3e5)를 그대로 통과시키고, 그 밖의 잘못된 값에는
 * 칸을 말없이 비워 버려서 무엇이 잘못됐는지 보이지 않는다. 그래서 문자로 받고 직접 거른다.
 */
export function onlyDecimal(value: string, maxIntDigits: number, maxFractionDigits: number) {
  const cleaned = toHalfWidth(value).replace(/[^\d.]/g, "");
  const [head, ...rest] = cleaned.split(".");
  const int = head.slice(0, maxIntDigits);
  if (rest.length === 0) return int;
  return `${int}.${rest.join("").slice(0, maxFractionDigits)}`;
}

export const PHONE_LENGTH = 11;
export const BUSINESS_NUMBER_LENGTH = 10;
export const STAFF_PIN_LENGTH = 6;

/** 010으로 시작하는 11자리. 서버 규칙과 같은 문장을 화면에서도 쓴다. */
export const isPhone = (value: string) => /^010\d{8}$/.test(value);
export const isBusinessNumber = (value: string) => /^\d{10}$/.test(value);

/** 010-1234-5678 형태로 읽기 좋게. 입력 중에는 쓰지 않고 확인 화면에서만 쓴다. */
export function formatPhone(value: string) {
  const d = onlyDigits(value, PHONE_LENGTH);
  return d.length === PHONE_LENGTH ? `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}` : d;
}

export function formatBusinessNumber(value: string) {
  const d = onlyDigits(value, BUSINESS_NUMBER_LENGTH);
  return d.length === BUSINESS_NUMBER_LENGTH ? `${d.slice(0, 3)}-${d.slice(3, 5)}-${d.slice(5)}` : d;
}
