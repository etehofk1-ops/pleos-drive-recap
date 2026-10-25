#!/usr/bin/env bash
# 제출용 저장소(별도 폴더, 이력 분리)를 작업 저장소 HEAD로 갱신한다.
# 증거 영상·개발 메모·내부 보고 제외, 개인 PC 경로 치환. 커밋·푸시는 하지 않는다.
set -euo pipefail
SRC=/d/Projects/pleos-observe-lab; DST=/d/Projects/pleos-drive-recap-submit
find "$DST" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$SRC" archive HEAD | tar -x -C "$DST"
rm -rf "$DST/docs/evidence" "$DST/docs/dev-notes" "$DST"/_agent_report*
python - "$DST" <<'PY'
import pathlib, sys
old = r"node .\ai-proxy\server.mjs   # 키는 환경변수로 따로 넣는다"
new = r"node .\ai-proxy\server.mjs   # 키는 환경변수로 따로 넣는다"
for p in pathlib.Path(sys.argv[1]).rglob("*"):
    if ".git" in p.parts or not p.is_file() or p.suffix not in (".md", ".ps1", ".py", ".mjs", ".kt", ".txt", ".json", ".sh"): continue
    s = p.read_text(encoding="utf-8")
    if old in s: p.write_text(s.replace(old, new), encoding="utf-8")
PY
if grep -rIlF "eteho" "$DST" --exclude-dir=.git; then echo "개인 경로가 남아 있음"; exit 1; fi
echo "snapshot ok: $(du -sh --exclude=.git "$DST" | cut -f1)"
