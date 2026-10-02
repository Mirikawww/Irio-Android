#!/usr/bin/env python3
"""Regenerate app assets and Windows desktop resources.

Names and context windows come from OpenRouter /api/v1/models
(`name`, model-level `context_length` — not `top_provider.context_length`).
Reasoning and image flags still come from https://models.dev/api.json.
Run from the Irio repo root."""
import json, urllib.request

API = "https://models.dev/api.json"
OPENROUTER_MODELS_API = "https://openrouter.ai/api/v1/models"
OUT_NAMES = "app/src/main/assets/model-names.json"
OUT_REASONING = "app/src/main/assets/model-reasoning.json"
OUT_IMAGE = "app/src/main/assets/model-image.json"
OUT_CONTEXT = "app/src/main/assets/model-context.json"
OUT_IMAGE_DESKTOP = "desktop/src/main/resources/model-image.json"
OUT_CONTEXT_DESKTOP = "desktop/src/main/resources/model-context.json"

def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Irio model catalog"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


data = get_json(API)
openrouter_data = get_json(OPENROUTER_MODELS_API)
names, reasoning, image, context = {}, {}, {}, {}
for provider in data.values():
    if not isinstance(provider, dict):
        continue
    for mid, m in (provider.get("models") or {}).items():
        if not mid or not isinstance(m, dict):
            continue
        tail = mid.split("/")[-1]
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

# OpenRouter 的 name / context_length 是显示名和上下文窗口的来源。
# 去掉 name 的 provider 前缀。窗口用模型级 context_length，不用 top_provider。
# OpenRouter 未收录的别名只在下方补显示名，不编造窗口。
for model in openrouter_data.get("data", []):
    if not isinstance(model, dict):
        continue
    mid, name = model.get("id"), model.get("name")
    if not isinstance(mid, str) or not mid:
        continue
    tail = mid.split("/")[-1]
    if isinstance(name, str) and name:
        display = name.split(": ", 1)[-1]
        names[mid] = display
        names[tail] = display
    window = model.get("context_length")
    if isinstance(window, (int, float)) and not isinstance(window, bool) and window > 0:
        context[mid] = int(window)

# 裸 id 只在它本身不是另一条 OpenRouter 模型时写入；同一尾段取较大窗口。
tails = {}
for mid, value in context.items():
    tail = mid.split("/")[-1]
    if tail == mid or tail in context:
        continue
    prev = tails.get(tail)
    if prev is None or value > prev:
        tails[tail] = value
context.update(tails)

# OpenRouter 未收录的网关自定义生图模型（models.dev 也未收录）在此补充，避免目录命中失败：
# 例：gpt-image-2.5（api.yjs.im Image 组，无上游记录）。
image.setdefault("gpt-image-2.5", True)
names.setdefault("gpt-image-2.5", "GPT Image 2.5")

# OpenAI Responses compact 别名以及 OpenRouter 官方名裁决：
# OpenRouter API 不公开 compact 别名，但它们是实际可用的 OpenAI Responses 模型名。
names["gpt-5.5-openai-compact"] = "GPT-5.5 OpenAI Compact"
names["gpt-5.6-sol-openai-compact"] = "GPT-5.6 Sol OpenAI Compact"
names["gpt-5.6-terra-openai-compact"] = "GPT-5.6 Terra OpenAI Compact"
names["gpt-6-astra"] = "GPT-6 Astra"
names["gpt-6-sol"] = "GPT-6 Sol"
names["openai/gpt-6-astra"] = "GPT-6 Astra"
names["openai/gpt-6-sol"] = "GPT-6 Sol"
# OpenRouter 未收录的 OpenAI 新模型别名，保持官方 GPT 连字符写法。
names["gpt-5.6"] = "GPT-5.6"
names["openai/gpt-5.6"] = "GPT-5.6"
names["gpt-5.3-codex-spark"] = "GPT-5.3-Codex-Spark"
names["openai/gpt-5.3-codex-spark"] = "GPT-5.3-Codex-Spark"

# OpenRouter 官方名分裂时的人工裁决（setdefault 首次命中会随数据源顺序漂移）：
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
