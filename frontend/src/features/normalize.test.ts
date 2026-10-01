import { describe, expect, it } from "vitest";
import { formatTel, isStorePhone } from "./normalize";

describe("매장 전화", () => {
  it("유선·휴대전화·대표번호를 읽기 좋게 나눈다", () => {
    expect(formatTel("021234567")).toBe("02-123-4567");
    expect(formatTel("0212345678")).toBe("02-1234-5678");
    expect(formatTel("0311234567")).toBe("031-123-4567");
    expect(formatTel("01012345678")).toBe("010-1234-5678");
    expect(formatTel("15881234")).toBe("1588-1234");
    expect(formatTel("12345")).toBe("12345");
  });

  it("서버 Inputs.storePhone과 같은 값만 받는다", () => {
    for (const ok of ["021234567", "0311234567", "01012345678", "15881234"]) expect(isStorePhone(ok)).toBe(true);
    for (const bad of ["12345", "0101234", "1234567890", "010123456789"]) expect(isStorePhone(bad)).toBe(false);
  });
});
