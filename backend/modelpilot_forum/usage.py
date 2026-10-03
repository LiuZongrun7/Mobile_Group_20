"""各家的 usage 字段归一化。

2026-09-30 之前这里叫 `relay.py`，整个模块都是"帮用户转发到他自己的上游"那一套
（SSRF 防护、逐跳头处理、流式解析……）。中转整块砍掉之后，只有 `split_usage`
还有人用——智能体调上游之后要把响应里的 usage 归一成四个桶；将来"用户提问、
后端调模型"那条路也会用它。其余函数（`upstream_target` / `request_headers` /
`stream_usage` / `upstream_path` / `join` / `provider_for` / `usage_from_body`）
随转发一起删了，`TokenTrail_Game` 那份归档里也没有它们，要恢复见 git 历史。

`provider_for`（上游域名 → `Provider` 枚举）以后接第二个模型 API 时大概还要写一遍，
那时候再按新大纲里"能力表"的形状重新设计，别把现在这版按域名猜的搬回来。
"""


def split_usage(usage, model, service_tier=None):
    """把各家的 usage 归一成四个桶。

    `input` **只装未命中缓存的那部分**（`CONTRACTS.md` §8 明确过：字面量口径写在这里，
    因为两种读法都讲得通而差别很大）。已经含了缓存的三家必须减掉，否则 cacheRead
    会被算两遍钱。
    """
    if not isinstance(usage, dict):
        return None
    input_tokens = usage.get("prompt_tokens")
    output_tokens = usage.get("completion_tokens")
    if input_tokens is None and output_tokens is None:
        # Anthropic 那套字段名：input_tokens 本身就是「未命中缓存」的部分，
        # 缓存命中单独给，所以不用减。
        input_tokens = usage.get("input_tokens")
        output_tokens = usage.get("output_tokens")
        if input_tokens is None and output_tokens is None:
            return None
    prompt_details = usage.get("prompt_tokens_details") or usage.get("prompt_cache_details") or {}
    cache_read = (usage.get("prompt_cache_hit_tokens")
                  if usage.get("prompt_cache_hit_tokens") is not None
                  else prompt_details.get("cached_tokens"))
    cache_write = (usage.get("cache_creation_input_tokens")
                   if usage.get("cache_creation_input_tokens") is not None
                   else prompt_details.get("cache_write_tokens"))
    # OpenAI / DeepSeek 的 prompt_tokens 是「输入总量」，得把缓存那两段减掉。
    # Anthropic 的 input_tokens 已经是未命中部分，减了会变成负数，所以要判断。
    cache_miss = usage.get("prompt_cache_miss_tokens")
    if cache_miss is not None:
        uncached = cache_miss
    elif usage.get("input_tokens") is not None and usage.get("prompt_tokens") is None:
        uncached = input_tokens
    else:
        uncached = max(int(input_tokens or 0) - int(cache_read or 0) - int(cache_write or 0), 0)
    return {"model": model or "unknown", "serviceTier": service_tier,
            "input": max(int(uncached or 0), 0), "cacheRead": max(int(cache_read or 0), 0),
            "cacheWrite": max(int(cache_write or 0), 0), "output": max(int(output_tokens or 0), 0),
            "calls": 1}
