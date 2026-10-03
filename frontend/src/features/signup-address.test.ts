import { describe, expect, it } from "vitest";
import { combineSignupAddress } from "./signup-address";

describe("회원가입 매장 주소", () => {
  it("검색 주소와 상세주소를 하나의 저장 주소로 합친다", () => {
    expect(combineSignupAddress(" 서울시 마포구 와우산로 1 ", " 2층 201호 ")).toBe(
      "서울시 마포구 와우산로 1, 2층 201호",
    );
  });
});
