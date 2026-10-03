export const SIGNUP_ADDRESS_MAX_LENGTH = 255;

/** 검색 주소와 사용자가 입력한 층·호수를 서버의 단일 매장 주소 값으로 합친다. */
export function combineSignupAddress(address: string, detailAddress: string) {
  return [address.trim(), detailAddress.trim()].filter(Boolean).join(", ");
}
