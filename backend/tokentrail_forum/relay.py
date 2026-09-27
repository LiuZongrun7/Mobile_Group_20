"""中转核心：把请求原样转发到上游，顺手把 usage 记下来。

## 为什么要这个模块

`docs/DATA_SOURCES.md` 原来只有「拉取」和「导入」两条取数路径，两条都要
解析 provider 的账单文件（BOM、列名、人民币换算、峰谷价）。代理路径把这件事
换掉了：**响应体里的 usage 就是账单口径本身**，不用解析、不用维护价目表，
而且拿到的是逐次调用而不是时间桶（`CONTRACTS.md` §4 里 OpenAI 那种桶行
做不了按会话分析，这里没有这个问题）。

## 三条设计约束（都是刻意的）

1. **不改格式。** 请求体和响应体都原样穿过，不做 Responses↔Chat 这类协议转换。
   上游格式由用户在 cc-switch 里自己选对。代价是用户选错格式会直接看到上游报错，
   好处是这个模块不需要理解任何一家的协议语义。
2. **不流式改写。** 流式响应按块透传，只在旁边**观察**字节流抽 usage，
   一个字节都不重排（见 `stream_usage`）。
3. **上游地址不是随便填的。** 这是个 SSRF 面：用户能指定 URL，服务器就替他发请求。
   `upstream_target` 是唯一的出口，所有校验都在那里。
"""
import ipaddress
import json
import socket
from urllib.parse import urlsplit

from fastapi import HTTPException

from .relay_store import json_body


# 逐跳首部（RFC 9110 §7.6.1）：转发时必须丢掉，它们描述的是「这一段连接」，
# 不是端到端的消息。`authorization` 也在里面，但理由不同——上游那个头要换成
# 用户的真 key，不能把我们的 relay key 漏给上游。
HOP_BY_HOP = {"host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailer", "transfer-encoding", "upgrade", "content-length",
              "authorization", "accept-encoding"}


def _reject_private(address, host):
    """拒绝指向内网/回环/链路本地的上游。

    没有这一条，用户注册一个 `http://169.254.169.254/latest/meta-data/`
    就能拿我们的服务器当跳板读云元数据，或者扫内网。用户「有权填任意 URL」
    不代表「有权让我们去请求任意 URL」，这两件事必须分开。
    """
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError:
        raise HTTPException(400, "Upstream host did not resolve to an address") from None
    if parsed.is_private or parsed.is_loopback or parsed.is_link_local or parsed.is_reserved or parsed.is_multicast:
        raise HTTPException(400, f"Upstream host is not routable on the public internet: {host}")


def upstream_target(upstream_url, allowed_hosts=(), allow_private=False, self_hosts=()):
    """校验并解析上游地址。**这是唯一允许发起出站请求的出口。**

    返回 `(规范化的 base URL, 主机名)`。私网地址默认拒绝，只有显式开启
    （测试里指向本机假上游时才用）才放行。
    """
    parsed = urlsplit(upstream_url or "")
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(400, "Upstream URL must be an absolute http(s) URL")
    if parsed.username or parsed.password:
        raise HTTPException(400, "Upstream URL must not contain credentials")
    if parsed.fragment or parsed.query:
        raise HTTPException(400, "Upstream URL must not contain a query or fragment")
    host = parsed.hostname.lower().rstrip(".")
    if self_hosts and host in {value.lower() for value in self_hosts}:
        # 指回自己会变成转发环，请求自己喂自己，日志和用量都说不清。
        raise HTTPException(400, "Upstream URL must not point at the relay itself")
    if allowed_hosts:
        if host not in {value.lower().lstrip(".") for value in allowed_hosts}:
            raise HTTPException(400, f"Upstream host is not in the allowed list: {host}")
    elif allow_private:
        pass
    else:
        try:
            infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80),
                                       proto=socket.IPPROTO_TCP)
        except socket.gaierror:
            raise HTTPException(400, f"Upstream host does not resolve: {host}") from None
        for info in infos:
            _reject_private(info[4][0], host)
    base = f"{parsed.scheme}://{parsed.netloc}{parsed.path.rstrip('/')}"
    return base, host


def request_headers(headers, upstream_secret):
    """把客户端的头复制一份，换掉认证、丢掉逐跳首部。"""
    forwarded = {name: value for name, value in headers.items() if name.lower() not in HOP_BY_HOP}
    if upstream_secret:
        # 上游拿到的是用户自己的真 key，不是 relay key。
        # **前缀要在这里补齐**：用户在 App 里填的通常是裸 key（`sk-...`），
        # 而我们收到的可能带不带 `Bearer ` 都不一定。统一存裸 key、统一在这里加前缀，
        # 两边就不会各按各的理解拼一次（那是加两次或一次都不加的来源）。
        forwarded["authorization"] = "Bearer " + upstream_secret
    return forwarded


def normalize_upstream_secret(value):
    """把用户填的上游凭据归一成裸 key。空值返回 None（调用方当校验错误处理）。

    按**空白切词**而不是 `startswith("bearer ")` + 定长切片，因为后者有两种错法：
    `"Bearer  "` strip 完只剩 `"Bearer"`（没有尾空格，前缀匹配不上，于是一个空凭据
    被当成有效值存进去），以及大小写/空格数一变就少切一个字符——两种的症状都是
    「上游说你的 key 不对」，完全看不出是我们解析错。
    """
    parts = (value or "").split()
    if parts and parts[0].lower() == "bearer":
        parts = parts[1:]
    # 只取第一个词：key 本身不含空格，多出来的部分是用户误粘的尾巴。
    return parts[0] if parts else None


def usage_from_body(body):
    """从非流式响应体里抽 usage。抽不到返回 None——**不能返回全 0**，
    「这次没有 usage」和「这次花了 0 token」是两件事（`CONTRACTS.md` §4 那条底线）。"""
    document = json_body(body)
    if not isinstance(document, dict):
        return None
    usage = document.get("usage")
    if not isinstance(usage, dict):
        return None
    return split_usage(usage, document.get("model"), document.get("service_tier"))


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


def stream_usage(chunk, buffer):
    """从流式响应的字节里抽 usage，**不改动字节**。

    上游的流式响应是 SSE，`data:` 行之间用换行分隔。分块边界不保证落在行边界上，
    所以留一个 buffer 等下一块补齐；buffer 超过 `MAX_BUFFER` 就丢掉最前面那部分，
    防止一个畸形响应把内存吃光（丢掉的只是解析用的副本，发给客户端的字节不受影响）。
    """
    buffer += chunk
    found = None
    while b"\n" in buffer:
        line, buffer = buffer.split(b"\n", 1)
        line = line.strip()
        if not line.startswith(b"data:"):
            continue
        payload = line[5:].strip()
        if payload == b"[DONE]" or not payload:
            continue
        # 大多数块没有 usage，先做一次便宜的包含检查，避免每块都解析 JSON。
        if b'"usage"' not in payload:
            continue
        document = json_body(payload)
        if isinstance(document, dict):
            candidate = split_usage(document.get("usage") or {}, document.get("model"))
            if candidate is not None:
                found = candidate
    return found, buffer[-MAX_BUFFER:]


MAX_BUFFER = 256 * 1024


def upstream_path(incoming_path, relay_prefix):
    """剥掉中转自己那一层前缀，得到要发给上游的路径。

    例：`/api/relay/v1/chat/completions` → `/v1/chat/completions`；
    测试区 `/test-api/relay/v1/...` 同样处理。

    只做一件事：**去掉我们加的那一层**。上游 base 里已经带的路径段
    （`https://api.deepseek.com` 或 `.../v4`）由 `join` 负责，两者不重叠。
    """
    remainder = incoming_path
    if remainder.startswith(relay_prefix):
        remainder = remainder[len(relay_prefix):]
    remainder = "/" + remainder.lstrip("/")
    return remainder


def join(base, path):
    """把上游 base 和路径拼起来。

    刻意**不用 `urljoin`**：`urljoin("https://x/v1", "/v1/a")` 会丢掉 `/v1`
    （`urljoin` 认的是「绝对路径替换」），而我们要的是「base 的路径段保留、
    再往后接」。用字符串拼接反而语义清楚：base 已经 rstrip 过 `/`，
    path 保证以 `/` 开头。
    """
    return base.rstrip("/") + "/" + path.lstrip("/")


def provider_for(host):
    """按域名推提供方。认得出来就填，认不出来返回 None——**不猜**，
    猜错会让 `DailyUsage` 挂到错误的 provider 上，比留空难查得多。"""
    host = (host or "").lower()
    if "deepseek" in host:
        return "DEEPSEEK"
    if "xiaomimimo" in host or "mimo" in host:
        return "MIMO"
    if "openai" in host:
        return "OPENAI"
    return None
