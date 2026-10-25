"""에뮬레이터 내비용 NMEA 위치 공급기.

에뮬레이터 기본 GNSS가 내보내는 NMEA 문장은 체크섬이 '*47'로 고정돼 내비(NmeaParser)가 모두 버린다.
이 스크립트는 체크섬을 올바르게 계산한 $GPRMC/$GPGGA를 `adb emu geo nmea`로 1초마다 넣는다.

사용:
  python -I scripts/nmea-feed.py hold <lat> <lon> [초]
  python -I scripts/nmea-feed.py path <경로.json> [속도kmh]     # json: [[lat,lon], ...]
"""
import json, math, os, subprocess, sys, time
from datetime import datetime, timezone

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")


def cks(body: str) -> str:
    c = 0
    for ch in body:
        c ^= ord(ch)
    return f"{c:02X}"


def dm(v: float, lat: bool) -> tuple[str, str]:
    hemi = ("N" if v >= 0 else "S") if lat else ("E" if v >= 0 else "W")
    v = abs(v)
    d = int(v)
    m = (v - d) * 60
    return (f"{d:02d}{m:07.4f}" if lat else f"{d:03d}{m:07.4f}"), hemi


def sentences(lat: float, lon: float, kmh: float, heading: float) -> list[str]:
    now = datetime.now(timezone.utc)
    hhmmss = now.strftime("%H%M%S") + ".00"
    ddmmyy = now.strftime("%d%m%y")
    la, lah = dm(lat, True)
    lo, loh = dm(lon, False)
    knots = kmh / 1.852
    rmc = f"GPRMC,{hhmmss},A,{la},{lah},{lo},{loh},{knots:.2f},{heading:.1f},{ddmmyy},,,A"
    gga = f"GPGGA,{hhmmss},{la},{lah},{lo},{loh},1,10,0.8,50.0,M,20.0,M,,"
    return [f"${s}*{cks(s)}" for s in (gga, rmc)]


def send(lat, lon, kmh=0.0, heading=0.0):
    for s in sentences(lat, lon, kmh, heading):
        subprocess.run([ADB, "-s", "emulator-5554", "emu", "geo", "nmea", s], capture_output=True)


def bearing(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lo2 - lo1) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(lo2 - lo1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def dist_m(a, b):
    r = 6371000
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "hold":
        lat, lon = float(sys.argv[2]), float(sys.argv[3])
        secs = int(sys.argv[4]) if len(sys.argv) > 4 else 30
        for _ in range(secs):
            send(lat, lon)
            time.sleep(1)
    elif mode == "path":
        pts = json.load(open(sys.argv[2], encoding="utf-8"))
        kmh = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0
        step = kmh / 3.6  # m per second
        for a, b in zip(pts, pts[1:]):
            d = dist_m(a, b)
            hd = bearing(a, b)
            n = max(1, int(d / step))
            for i in range(n):
                t = i / n
                send(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, kmh, hd)
                print(f"{a[0] + (b[0] - a[0]) * t:.6f},{a[1] + (b[1] - a[1]) * t:.6f} hd={hd:.0f}", flush=True)
                time.sleep(1)
        for _ in range(5):
            send(pts[-1][0], pts[-1][1])
            time.sleep(1)
