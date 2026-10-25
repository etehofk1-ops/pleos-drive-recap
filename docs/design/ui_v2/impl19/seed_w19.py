"""1.9.0 시험용: 에뮬레이터의 디버그 앱에 지난 날 집계(recap-counts-v1)와 모은 순간(recap-good-v1) 파일을 심는다.
앱 코드에는 시험용 훅이 없다. 파일 형식은 CountCodec(V1/V3/V4)·GoodCodec 그대로이고, run-as(디버그 빌드만 가능)로 복사한다.
사용: python seed_w19.py <출력 폴더>   (오늘이 2026-10-26인 에뮬레이터 기준; 날짜는 아래 상수를 바꾼다)"""
import struct, sys, pathlib
out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
def utf(s): b = s.encode(); return struct.pack('>H', len(b)) + b
def ints(*v): return b''.join(struct.pack('>i', x) for x in v)
# 날짜별: V4(켬 거리 있음) / V3(켜 둔 채 포함, 거리 없음) / V1(가장 옛 형식)
(out / '2026-10-25.bin').write_bytes(ints(0x434E5434) + utf('2026-10-25') + ints(2, 1, 2, 0, 0, 0, 0, 0, 60, 2))
(out / '2026-10-24.bin').write_bytes(ints(0x434E5433) + utf('2026-10-24') + ints(3, 0, 1, 0, 0, 0, 0, 1))
(out / '2026-10-22.bin').write_bytes(ints(0x434E5434) + utf('2026-10-22') + ints(2, 1, 3, 0, 0, 0, 0, 0, 90, 3))
(out / '2026-10-21.bin').write_bytes(ints(0x434E5431) + utf('2026-10-21') + ints(4, 0, 1, 0))
# 모은 잘한 순간: 어제까지 8개, 연속 2, 최고 4 (오늘 첫 잘한 순간이 9번째, 다음이 10번째 이정표)
(out / 'state.bin').write_bytes(ints(0x474F4431) + utf('2026-10-25') + ints(0, 0, 0, 8, 2, 4))
