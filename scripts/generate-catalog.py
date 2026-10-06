#!/usr/bin/env python3
"""Regenerate app assets and Windows desktop resources.

Names, reasoning and context windows come from OpenRouter /api/v1/models
(`name`, model-level `context_length` — not `top_provider.context_length`).
Image flags still come from https://models.dev/api.json.
Run from the Irio repo root; --reasoning-only leaves other catalogs untouched."""
import json, sys, urllib.request
from typing import Any

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


reasoning_only = "--reasoning-only" in sys.argv[1:]
data = {} if reasoning_only else get_json(API)
openrouter_data = get_json(OPENROUTER_MODELS_API)
names, reasoning, image, context = {}, {}, {}, {}
for provider in data.values():
    if not isinstance(provider, dict):
        continue
    for mid, m in (provider.get("models") or {}).items():
        if not mid or not isinstance(m, dict):
            continue
        tail = mid.split("/")[-1]
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
    # 动态路由器没有固定思考能力；缺少元数据时不把未知当作不支持。
    metadata = model.get("reasoning")
    parameters = model.get("supported_parameters")
    if mid not in ("openrouter/auto", "openrouter/free") and (
        isinstance(metadata, dict) or isinstance(parameters, list)
    ):
        supports = isinstance(metadata, dict) or any(
            parameter in ("reasoning", "reasoning_effort", "include_reasoning")
            for parameter in (parameters or [])
        )
        entry: dict[str, Any] = {"supports": supports}
        if isinstance(metadata, dict):
            mandatory = metadata.get("mandatory")
            if isinstance(mandatory, bool):
                entry["toggleable"] = not mandatory
            efforts = metadata.get("supported_efforts")
            if isinstance(efforts, list):
                entry["options"] = [effort for effort in efforts if isinstance(effort, str)]
        reasoning[mid] = entry
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

# 裸 id 查找与其他目录一致；精确 id 优先，同名别名按最宽松的能力合并。
reasoning_tails = {}
for mid, entry in reasoning.items():
    tail = mid.split("/")[-1]
    if tail == mid or tail in reasoning:
        continue
    previous = reasoning_tails.get(tail)
    if previous is None:
        reasoning_tails[tail] = dict(entry)
        continue
    supported = [value for value in (previous, entry) if value["supports"]]
    merged: dict[str, Any] = {"supports": bool(supported)}
    if any(value.get("toggleable") is True for value in supported):
        merged["toggleable"] = True
    elif supported and all(value.get("toggleable") is False for value in supported):
        merged["toggleable"] = False
    if "options" in previous or "options" in entry:
        merged["options"] = sorted(set(previous.get("options", [])) | set(entry.get("options", [])))
    reasoning_tails[tail] = merged
reasoning.update(reasoning_tails)

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
    (OUT_IMAGE_DESKTOP, image),
    (OUT_CONTEXT_DESKTOP, context),
):
    if reasoning_only and path != OUT_REASONING:
        continue
    with open(path, "w", encoding="utf-8") as output:
        json.dump(payload, output, separators=(",", ":"), ensure_ascii=False)
print(f"names={len(names)} reasoning={len(reasoning)} image={len(image)} context={len(context)}")
