/**
 * 인트로의 큰 윷. 게임과 같은 전통 장작윷 실루엣이라 손님이 여기서 본 모양이
 * 그대로 던져진다. 물리나 3D는 쓰지 않는다. QR을 찍고 처음 만나는 화면이라
 * 가장 가벼워야 하고, 게임 씬은 손대지 않는다는 규칙도 그대로 지킨다.
 *
 * 배(belly)는 평평한 면, 등(back)은 둥근 면. 세 개가 배로 누웠으니 '걸'이다.
 * 한 짝에만 찍힌 표시는 실제 윷의 백도 표식이다.
 */
import type { CSSProperties } from "react";

const HALF_LENGTH = 84;
const HALF_WIDTH = 17;

type Stick = { y: number; rot: number; face: "belly" | "back"; marked?: boolean };

const STICKS: Stick[] = [
  { y: 40, rot: -6, face: "belly" },
  { y: 88, rot: 4, face: "back" },
  { y: 136, rot: -3, face: "belly", marked: true },
  { y: 184, rot: 7, face: "belly" },
];

export function YutFan() {
  return (
    <svg
      className="yut-fan"
      viewBox="0 0 320 224"
      role="img"
      aria-label="윷 네 짝으로 걸이 나온 모습"
    >
      <defs>
        <filter id="yut-shadow" x="-20%" y="-60%" width="140%" height="220%">
          <feGaussianBlur stdDeviation="6" />
        </filter>
      </defs>

      {STICKS.map((stick, i) => (
        <g key={stick.y} transform={`translate(160 ${stick.y}) rotate(${stick.rot})`}>
          <g className="yut-fan-stick" style={{ "--fall": `${560 + i * 100}ms` } as CSSProperties}>
            <ellipse
              className="yut-fan-shadow"
              cx="0"
              cy={HALF_WIDTH + 6}
              rx={HALF_LENGTH - 6}
              ry="7"
              filter="url(#yut-shadow)"
            />
            <rect
              x={-HALF_LENGTH}
              y={-HALF_WIDTH}
              width={HALF_LENGTH * 2}
              height={HALF_WIDTH * 2}
              rx="9"
              className={stick.face === "belly" ? "yut-fan-belly" : "yut-fan-back"}
            />
            {stick.face === "belly" ? (
              <>
                {/* 평평한 배에는 나뭇결 한 줄만 */}
                <line
                  className="yut-fan-grain"
                  x1={-HALF_LENGTH + 16}
                  y1="0"
                  x2={HALF_LENGTH - 16}
                  y2="0"
                />
                {stick.marked && (
                  <g className="yut-fan-mark">
                    <line x1="-9" y1="-9" x2="9" y2="9" />
                    <line x1="9" y1="-9" x2="-9" y2="9" />
                  </g>
                )}
              </>
            ) : (
              /* 둥근 등은 능선을 따라 빛을 받는다 */
              <rect
                className="yut-fan-ridge"
                x={-HALF_LENGTH + 10}
                y={-HALF_WIDTH + 5}
                width={HALF_LENGTH * 2 - 20}
                height="11"
                rx="5.5"
              />
            )}
          </g>
        </g>
      ))}
    </svg>
  );
}
