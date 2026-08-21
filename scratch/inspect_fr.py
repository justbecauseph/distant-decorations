import zipfile

with zipfile.ZipFile(r'C:\Users\markj\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-rendering-v1\25.3.2+515ac5339e\c60fb9b8b8f321a8fd49a2f84ef4fbc967a02058\fabric-rendering-v1-25.3.2+515ac5339e.jar') as z:
    for name in z.namelist():
        if 'mixin' in name.lower():
            print(name)
