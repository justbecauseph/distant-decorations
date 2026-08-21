import zipfile

with zipfile.ZipFile(r'C:\Users\markj\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-rendering-v1\25.3.2+515ac5339e\36b36d1c0c755eb28844672c52d0a16ec38029ab\fabric-rendering-v1-25.3.2+515ac5339e-sources.jar') as z:
    for name in z.namelist():
        if name.endswith('.java'):
            content = z.read(name).decode('utf-8', errors='ignore')
            if 'COLLECT_SUBMITS' in content or 'renderFrame' in content:
                print(f"=== {name} ===")
                for line in content.splitlines():
                    if 'COLLECT_SUBMITS' in line:
                        print("  ", line)
