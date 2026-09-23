#!/usr/bin/env python3
"""Regenerate app assets and Windows desktop resources from https://models.dev/api.json.
Run from the Irio repo root."""
import json, urllib.request

API = "https://models.dev/api.json"
OUT_NAMES = "app/src/main/assets/model-names.json"
OUT_REASONING = "app/src/main/assets/model-reasoning.json"
OUT_IMAGE = "app/src/main/assets/model-image.json"
OUT_CONTEXT = "app/src/main/assets/model-context.json"
OUT_IMAGE_DESKTOP = "desktop/src/main/resources/model-image.json"
OUT_CONTEXT_DESKTOP = "desktop/src/main/resources/model-context.json"

data = json.load(urllib.request.urlopen(API, timeout=30))
names, reasoning, image, context = {}, {}, {}, {}
for provider in data.values():
    if not isinstance(provider, dict):
        continue
    for mid, m in (provider.get("models") or {}).items():
        if not mid or not isinstance(m, dict):
            continue
        tail = mid.split("/")[-1]
        if m.get("name"):
            names.setdefault(mid, m["name"])
            if tail != mid:
                names.setdefault(tail, m["name"])
        ent = {"supports": bool(m.get("reasoning"))}
        opts = m.get("reasoning_options")
        if isinstance(opts, list):
            vals = [v for o in opts if isinstance(o, dict)
                    for v in (o.get("values") or []) if isinstance(v, str)]
            if vals:
                ent["options"] = vals
        prev = reasoning.get(mid)
        if prev:
            prev["supports"] = prev["supports"] or ent["supports"]
            if "options" in ent:
                prev["options"] = sorted(set(prev.get("options") or []) | set(ent["options"]))
        else:
            reasoning[mid] = ent
        mod = m.get("modalities")
        outs = (mod.get("output") or []) if isinstance(mod, dict) else []
        if "image" in outs:
            image.setdefault(mid, True)
            if tail != mid:
                image.setdefault(tail, True)
        lim = m.get("limit")
        window = lim.get("context") if isinstance(lim, dict) else None
        if isinstance(window, (int, float)) and window > 0:
            value = int(window)
            prev = context.get(mid)
            if prev is None or value > prev:
                context[mid] = value
            if tail != mid:
                prev_tail = context.get(tail)
                if prev_tail is None or value > prev_tail:
                    context[tail] = value

# 网关自定义的生图模型（models.dev 未收录）在此补充，避免目录命中失败：
# 例：gpt-image-2.5（api.yjs.im Image 组，无上游记录）。
image.setdefault("gpt-image-2.5", True)
names.setdefault("gpt-image-2.5", "GPT Image 2.5")

# 上游官方名分裂时的人工裁决（setdefault 首次命中会随数据源顺序漂移）：
# gpt-image-2：kenari/poe/azure 标 "GPT-Image-2"（带连字符），vercel/fastrouter 标 "GPT Image 2"；
# 产品内 2.5 系列均不带连字符（GPT Image 2.5 …），统一为 "GPT Image 2"。
names["gpt-image-2"] = "GPT Image 2"
names.setdefault("gpt-image-1", "GPT Image 1")

for path, payload in (
    (OUT_NAMES, names),
    (OUT_REASONING, reasoning),
    (OUT_IMAGE, image),
    (OUT_CONTEXT, context),
):
    json.dump(payload, open(path, "w"), separators=(",", ":"), ensure_ascii=False)
json.dump(image, open(OUT_IMAGE_DESKTOP, "w"), separators=(",", ":"))
json.dump(context, open(OUT_CONTEXT_DESKTOP, "w"), separators=(",", ":"))
print(f"names={len(names)} reasoning={len(reasoning)} image={len(image)} context={len(context)}")
