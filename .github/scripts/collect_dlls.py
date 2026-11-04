"""Собирает нативные библиотеки компьютерной версии в desktop/resources/windows-x64."""
import os
import shutil
import sys
import tarfile
import urllib.request

# Консоль Windows по умолчанию не в UTF-8
sys.stdout.reconfigure(encoding="utf-8")

dst = os.path.join("desktop", "resources", "windows-x64")
os.makedirs(dst, exist_ok=True)


def collect(build_dir, only=None):
    for root, dirs, files in os.walk(build_dir):
        # исходники и служебные папки CMake пропускаем — там нет нужных DLL
        dirs[:] = [d for d in dirs if not (d.endswith("-src") or d.endswith("-subbuild") or d == "CMakeFiles")]
        for f in files:
            if not f.lower().endswith(".dll"):
                continue
            if only and f.lower() != only.lower():
                continue
            if "vulkan-shaders-gen" in f:
                continue
            shutil.copy2(os.path.join(root, f), os.path.join(dst, f))
            print("  +", f, "<-", root)


print("llama.cpp:")
collect("build-llm")
print("stable-diffusion.cpp:")
collect("build-sd-vk", "sdengine_vk.dll")
collect("build-sd-cpu", "sdengine_cpu.dll")

ver = os.environ.get("SHERPA_VERSION", "1.13.8")
url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{ver}/sherpa-onnx-v{ver}-win-x64-jni.tar.bz2"
print("sherpa-onnx:", url)
archive = "sherpa.tar.bz2"
urllib.request.urlretrieve(url, archive)
with tarfile.open(archive, "r:bz2") as tar:
    for m in tar.getmembers():
        if m.isfile() and m.name.lower().endswith(".dll"):
            name = os.path.basename(m.name)
            with tar.extractfile(m) as src, open(os.path.join(dst, name), "wb") as out:
                shutil.copyfileobj(src, out)
            print("  +", name)

print("Среда выполнения Visual C++:")
sysdir = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
for n in ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll", "vcomp140.dll"]:
    p = os.path.join(sysdir, n)
    if os.path.exists(p):
        shutil.copy2(p, os.path.join(dst, n))
        print("  +", n)

required = ["neuropocket.dll", "llama.dll", "ggml.dll", "ggml-base.dll", "mtmd.dll", "ggml-vulkan.dll",
            "sdengine_vk.dll", "sdengine_cpu.dll", "sherpa-onnx-jni.dll", "onnxruntime.dll"]
have = {f.lower() for f in os.listdir(dst)}
missing = [r for r in required if r not in have]
print("\nВсего файлов:", len(have))
for f in sorted(have):
    print(f"  {f:40s} {os.path.getsize(os.path.join(dst, f)) // 1024:>8} КБ")
if missing:
    print("НЕ НАЙДЕНЫ:", missing)
    sys.exit(1)
if not any(f.startswith("ggml-cpu") for f in have):
    print("НЕ НАЙДЕН ggml-cpu*.dll")
    sys.exit(1)
