import urllib.request
import re

def analyze_profile(url, label):
    print(f"\n=======================================================")
    print(f"  ANALYZING PROFILE: {label} ({url})")
    print(f"=======================================================")
    code = url.split('/')[-1]
    bytebin_url = f"https://bytebin.lucko.me/{code}"
    req = urllib.request.Request(bytebin_url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req) as resp:
            data = resp.read()
    except Exception as e:
        print(f"Failed to fetch {bytebin_url}: {e}")
        return

    print(f"Downloaded {len(data)} bytes")
    pattern = re.compile(rb'([a-zA-Z0-9_$/]+(?:\.[a-zA-Z0-9_$/]+)+)')
    matches = set()
    for match in pattern.finditer(data):
        s = match.group(0).decode('ascii', errors='ignore')
        if any(k in s.lower() for k in ['distantdecorations', 'fastpaintings', 'camerapture', 'voxy', 'sodium']):
            matches.add(s)

    for prefix, name in [
        ('distantdecorations', 'Distant Decorations'),
        ('fastpaintings', 'Fast Paintings'),
        ('camerapture', 'Camerapture'),
        ('voxy', 'Voxy'),
        ('sodium', 'Sodium')
    ]:
        sub = [m for m in matches if prefix in m.lower()]
        print(f"\n--- {name} ({len(sub)} unique symbols) ---")
        for s in sorted(sub)[:10]:
            print(f"  • {s}")

analyze_profile("https://spark.lucko.me/zL5RG9hC7w", "Client Multi-Mod Profile (zL5RG9hC7w)")
analyze_profile("https://spark.lucko.me/iGePOSRIyM", "Server Multi-Mod Profile (iGePOSRIyM)")
