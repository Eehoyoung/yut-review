// 이 게임을 이미 던졌는지(이 탭에서). 뒤로 가기로 던지기 화면에 돌아와도 새 판처럼 보이지 않게 한다.
// 한판 더는 같은 playId에 새 seed로 다시 던지므로 그때만 지운다.
const playedKey = (playId: string) => `yut:played:${playId}`;

export function markPlayed(playId: string) {
  try {
    sessionStorage.setItem(playedKey(playId), "1");
  } catch {
    // Private mode without storage still works; the server result is unchanged either way.
  }
}

export function wasPlayed(playId: string) {
  try {
    return sessionStorage.getItem(playedKey(playId)) === "1";
  } catch {
    return false;
  }
}

export function clearPlayed(playId: string) {
  try {
    sessionStorage.removeItem(playedKey(playId));
  } catch {
    // 지우지 못해도 서버가 같은 결과만 돌려주므로 결과가 바뀌지는 않는다.
  }
}
