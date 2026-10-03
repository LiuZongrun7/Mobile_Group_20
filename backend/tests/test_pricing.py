"""服务端价目表的测试。

三个重点：

1. **查不到价必须是 `None`，不是 0**（`CONTRACTS.md` §4 那条底线）。
2. **按「当天生效的那一版」查**——改了价目表不能让历史账单变。
3. **人民币 → 微美元的换算和 App 侧 `data/Money` 同值**，而且只在服务端做一次。
"""
import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.pricing import (CNY_PER_USD_MICROS, Pricing, cny_per_1m_to_usd_micros,
                                      cost_micros)
from tokentrail_forum.seed_pricing import SEED, install as install_seed
from tokentrail_forum.store import Store
from conftest import verifier
from helpers import (account_login, account_token, bearer, completion,
                      cst_millis, daily, insert_usage)
from test_seasons import TODAY, day, make_season_app

MONTH = "2026-09"


def make_pricing_app(path, seed=False):
    settings = Settings(str(path), "https://forum.example", relay_enabled=True,
                        pricing_seed=seed)
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    return create_app(settings, verifier(), day_provider=lambda: TODAY)


def pricing_list(api, relay_key=None, **query):
    relay_key = relay_key or account_token(api)
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/pricing" + suffix,
                   headers={"Authorization": "Bearer " + relay_key})


def daily(api, relay_key=None, **query):
    relay_key = relay_key or account_token(api)
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/daily" + suffix,
                   headers={"Authorization": "Bearer " + relay_key})


# ---- 换算率 -------------------------------------------------------------

def test_cny_to_usd_micros_matches_the_app_side_constant():
    """1 元 / 1M → 140845 微美元 / 1M（$0.140845）。

    App 侧 `data/Money.CNY_PER_USD_MICROS` 是 7_100_000，两处必须同值——
    不同的话同一笔钱会在两个地方显示成两个数，而且没人知道该信哪个。
    """
    assert CNY_PER_USD_MICROS == 7_100_000
    assert cny_per_1m_to_usd_micros(1) == 140_845
    assert cny_per_1m_to_usd_micros(4) == 563_380
    assert cny_per_1m_to_usd_micros(0.02) == 2_816


# ---- 查不到价 → None，不是 0 --------------------------------------------

def test_a_missing_rate_returns_none_not_zero():
    assert cost_micros({"input": 10**9, "cacheRead": 0, "cacheWrite": 0, "output": 0}, None) is None


def test_a_rate_missing_any_bucket_makes_the_whole_thing_unavailable():
    """四个桶里缺一个 → 整笔算不出来。

    逐项跳过缺失的那一项会得到一个「部分成本」，它和真实成本的差额
    没有任何地方体现，看起来却像个正常数字。
    """
    partial = {"input_micros": 140_845, "cache_read_micros": None,
               "cache_write_micros": 0, "output_micros": 563_380}
    assert cost_micros({"input": 1000, "cacheRead": 0, "cacheWrite": 0, "output": 0}, partial) is None


def test_cost_is_the_sum_of_the_four_buckets():
    # **键名是数据库列名**（`*_micros`），因为 `cost_micros` 收的是 `rate_for`
    # 返回的那一行。踩过：这里一度用短名，于是每一项都取到 None、整笔返回 None，
    # 而 `rateVersion` 却是填上的——症状是「有费率但算不出价」。
    rate = {"input_micros": 140_845, "cache_read_micros": 2_816,
            "cache_write_micros": 0, "output_micros": 563_380}
    tokens = {"input": 1_000_000, "cacheRead": 1_000_000, "cacheWrite": 0, "output": 1_000_000}
    assert cost_micros(tokens, rate) == 707_041


# ---- 按天查费率（改了价不能让历史变）------------------------------------

def test_a_later_rate_does_not_change_earlier_days(tmp_path):
    store = Store(str(tmp_path), "/api")
    pricing = Pricing(store)
    pricing.upsert("DEEPSEEK", "deepseek-chat", "2026-09-01",
                   {"input": 100, "cache_read": 0, "cache_write": 0, "output": 200},
                   "v1")
    pricing.upsert("DEEPSEEK", "deepseek-chat", "2026-09-20",
                   {"input": 999, "cache_read": 0, "cache_write": 0, "output": 999},
                   "v2")

    assert pricing.rate_for("DEEPSEEK", "deepseek-chat", "2026-09-10")["rate_version"] == "v1"
    # 生效当天就用新的
    assert pricing.rate_for("DEEPSEEK", "deepseek-chat", "2026-09-20")["rate_version"] == "v2"
    assert pricing.rate_for("DEEPSEEK", "deepseek-chat", "2026-09-27")["rate_version"] == "v2"
    # 生效日之前查不到（不是「回落到 v1 之外的什么东西」）
    assert pricing.rate_for("DEEPSEEK", "deepseek-chat", "2026-08-31") is None


def test_the_batch_lookup_keys_on_the_day_too(tmp_path):
    store = Store(str(tmp_path), "/api")
    pricing = Pricing(store)
    pricing.upsert("DEEPSEEK", "deepseek-chat", "2026-09-20",
                   {"input": 100, "cache_read": 0, "cache_write": 0, "output": 0}, "v1")
    found = pricing.rates_for_days([("DEEPSEEK", "deepseek-chat", "2026-09-10"),
                                    ("DEEPSEEK", "deepseek-chat", "2026-09-25")])
    # 同一个模型、不同的天 → 一个查得到一个查不到。键里不带天的话这两个会混成一个。
    assert found[("DEEPSEEK", "deepseek-chat", "2026-09-10")] is None
    assert found[("DEEPSEEK", "deepseek-chat", "2026-09-25")]["rate_version"] == "v1"


# ---- 种子 ---------------------------------------------------------------

def test_the_seed_is_the_measured_deepseek_data():
    """种子来自 `DATA_SOURCES.md` §4 的实测记录，不是编的。

    低谷档（cacheRead 0.02 / input 1 / output 4 元/1M）、cacheWrite 记 0
    （DeepSeek 不计缓存写入）。**只录 DeepSeek**——OpenAI 和 MiMo 没实测过，
    编一组看起来合理的数字会让成本功能看起来能用、实际全错。
    """
    providers = {entry[0] for entry in SEED}
    assert providers == {"DEEPSEEK"}, "only measured providers may be seeded"
    # 现役模型：官方定价页本身就是美元，直接乘 1e6 就是微美元，**不走汇率**
    flash = next(entry for entry in SEED if entry[1] == "deepseek-flash")
    assert flash[4] == {"input": 300_000, "cache_read": 6_000, "cache_write": 0,
                        "output": 1_200_000}, flash[4]
    pro = next(entry for entry in SEED if entry[1] == "deepseek-v4-pro")
    assert pro[4]["output"] == 3_960_000
    # 退役模型：来自那份实测导出（人民币），走汇率
    chat = next(entry for entry in SEED if entry[1] == "deepseek-chat")
    assert chat[4]["input"] == cny_per_1m_to_usd_micros(1)
    assert chat[4]["cache_read"] == cny_per_1m_to_usd_micros(0.02)
    assert chat[4]["output"] == cny_per_1m_to_usd_micros(4)
    assert chat[4]["cache_write"] == 0
    # 每条都要有出处，报告里要照抄
    assert all(entry[5].startswith("https://") for entry in SEED)


def test_installing_the_seed_twice_changes_nothing(tmp_path):
    store = Store(str(tmp_path), "/api")
    pricing = Pricing(store)
    added, skipped = install_seed(store, pricing)
    assert added == len(SEED) and skipped == 0
    added, skipped = install_seed(store, pricing)
    assert added == 0 and skipped == len(SEED)


def test_every_model_the_api_actually_serves_has_a_rate(tmp_path):
    """`/models` 现在只返回 `deepseek-flash` 和 `deepseek-v4-pro`。

    种子里没有它们的话，智能体自己的成本永远是 0——**而且看不出来**
    （`ownCostMicros: 0` 读起来像「这次问答不要钱」）。
    """
    seeded = {entry[1] for entry in SEED}
    assert {"deepseek-flash", "deepseek-v4-pro"} <= seeded, seeded


def test_installing_does_not_overwrite_a_corrected_rate(tmp_path):
    """种子里那一版已存在时**不覆盖**——生产库里可能有人后来录了更准的一版，
    无条件覆盖会在每次重启时把它悄悄冲掉。
    """
    store = Store(str(tmp_path), "/api")
    pricing = Pricing(store)
    install_seed(store, pricing)
    pricing.upsert("DEEPSEEK", "deepseek-chat", "2026-09-01",
                   {"input": 1, "cache_read": 0, "cache_write": 0, "output": 1},
                   "hand-corrected")
    install_seed(store, pricing)
    assert pricing.rate_for("DEEPSEEK", "deepseek-chat", "2026-09-01")["rate_version"] == "hand-corrected"


# ---- 日汇总上的成本 -----------------------------------------------------

def test_daily_cost_uses_the_rate_of_that_day(tmp_path):
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        # 09-25 那天 100 万 input + 200 万 output
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=2_000_000)
        body = daily(api).json()
        row = next(item for item in body["items"] if item["day"] == "2026-09-25")
        expected = cny_per_1m_to_usd_micros(1) * 1_000_000 // 1_000_000 \
            + cny_per_1m_to_usd_micros(4) * 2_000_000 // 1_000_000
        assert row["costMicros"] == expected, row
        assert row["rateVersion"] == "2026-09-deepseek-offpeak"
        assert body["pricingAvailable"] is True


def test_daily_cost_is_null_when_the_model_has_no_rate(tmp_path):
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        # 种子里没有这个模型 → 算不出价
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "OPENAI",
                     model="gpt-5", input_tokens=1_000_000)
        body = daily(api).json()
        row = body["items"][0]
        # **null 而不是 0**
        assert row["costMicros"] is None
        assert row["rateVersion"] is None
        assert body["pricingAvailable"] is False
        assert "gpt-5" in body["unpricedModels"]


def test_without_the_seed_flag_there_is_no_pricing(tmp_path):
    """默认不装种子：价目是**数据**，自动写入生产库会让「这批价哪来的」说不清。"""
    with TestClient(make_pricing_app(tmp_path, seed=False)) as api:
        TOKEN = account_token(api)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-25", 12),
                     "DEEPSEEK", input_tokens=1_000_000)
        body = daily(api).json()
        assert body["items"][0]["costMicros"] is None
        assert body["pricingAvailable"] is False


# ---- 预算的花销现在有真值了 ---------------------------------------------

def test_budget_spend_is_computed_from_priced_days(tmp_path):
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        # output 显式给 0：`insert_usage` 默认会塞 5 个 output token，
        # 那会多出 2 微美元（5 * 563380 // 1e6），让断言变得难读。
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        body = api.get(f"/api/relay/budgets/{MONTH}",
                       headers={"Authorization": "Bearer " + TOKEN}).json()
        assert body["spentMicros"] == cny_per_1m_to_usd_micros(1)
        assert body["pricingAvailable"] is True
        assert body["unpricedDays"] == []


def test_budget_reports_unpriced_days_instead_of_counting_them_as_zero(tmp_path):
    """有记录但算不出价的天，**不能当 0 加进去**——那会把「不知道」算成「没花」。

    它进 `unpricedDays`，同时 `pricingAvailable` 变 false，客户端据此显示
    「价格未知」而不是一个偏低的总额。
    """
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-26", 12), "OPENAI",
                     model="gpt-5", input_tokens=9_000_000, output=0)
        body = api.get(f"/api/relay/budgets/{MONTH}",
                       headers={"Authorization": "Bearer " + TOKEN}).json()
        # 有价的那天算进去了，没价的那天没有
        assert body["spentMicros"] == cny_per_1m_to_usd_micros(1)
        assert body["unpricedDays"] == ["2026-09-26"]
        assert body["pricingAvailable"] is False


# ---- 接口 ---------------------------------------------------------------

def test_pricing_listing_shows_rate_metadata(tmp_path):
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        body = pricing_list(api).json()
        assert body["count"] == len(SEED)
        assert body["cnyPerUsdMicros"] == CNY_PER_USD_MICROS
        # 报的是**数据库列名**（`*_micros`），因为录入接口用的就是这几个名字。
        assert set(body["buckets"]) == {"input_micros", "cache_read_micros",
                                        "cache_write_micros", "output_micros"}
        assert all("source_url" in item and "rate_version" in item for item in body["items"])


def test_recording_a_rate_round_trips(tmp_path):
    with TestClient(make_pricing_app(tmp_path)) as api:
        TOKEN = account_token(api)
        response = api.put("/api/relay/pricing/MIMO/mimo-v2.6-pro",
                           headers={"Authorization": "Bearer " + TOKEN},
                           json={"effectiveFrom": "2026-09-01", "rateVersion": "manual-v1",
                                 "inputMicrosPer1M": 100000, "cacheReadMicrosPer1M": 10000,
                                 "cacheWriteMicrosPer1M": 0, "outputMicrosPer1M": 400000,
                                 "sourceUrl": "https://mimo.mi.com/docs/en-US/price/pay-as-you-go"})
        assert response.status_code == 200, response.text
        assert response.json()["rate_version"] == "manual-v1"
        assert pricing_list(api, provider="MIMO").json()["count"] == 1


def test_pricing_convert_does_the_math_server_side(tmp_path):
    with TestClient(make_pricing_app(tmp_path)) as api:
        TOKEN = account_token(api)
        body = api.post("/api/relay/pricing/convert",
                        headers={"Authorization": "Bearer " + TOKEN},
                        json={"cnyPer1M": 4}).json()
        # 换算只有一处实现——让录入的人手算等于多一份实现，而且错了不报错。
        assert body["inputMicrosPer1M"] == cny_per_1m_to_usd_micros(4)
        assert body["cnyPerUsdMicros"] == CNY_PER_USD_MICROS


@pytest.mark.parametrize("bad", ["2026-2-01", "20260901", "2026-13-01", "2026-02-30", "abc"])
def test_bad_effective_dates_are_400_not_500(tmp_path, bad):
    with TestClient(make_pricing_app(tmp_path)) as api:
        TOKEN = account_token(api)
        response = api.put("/api/relay/pricing/DEEPSEEK/deepseek-chat",
                           headers={"Authorization": "Bearer " + TOKEN},
                           json={"effectiveFrom": bad, "rateVersion": "v", "inputMicrosPer1M": 1,
                                 "cacheReadMicrosPer1M": 0, "cacheWriteMicrosPer1M": 0,
                                 "outputMicrosPer1M": 1})
        assert response.status_code == 400, f"{bad} -> {response.status_code}"


def test_unknown_provider_is_rejected(tmp_path):
    with TestClient(make_pricing_app(tmp_path)) as api:
        TOKEN = account_token(api)
        response = api.put("/api/relay/pricing/NOT_A_PROVIDER/m",
                           headers={"Authorization": "Bearer " + TOKEN},
                           json={"effectiveFrom": "2026-09-01", "rateVersion": "v",
                                 "inputMicrosPer1M": 1, "cacheReadMicrosPer1M": 0,
                                 "cacheWriteMicrosPer1M": 0, "outputMicrosPer1M": 1})
        # `Provider` 是枚举，值域只有三个——塞别的客户端反序列化会炸。
        assert response.status_code == 400


def test_pricing_endpoints_require_a_relay_key(tmp_path):
    with TestClient(make_pricing_app(tmp_path, seed=True)) as api:
        TOKEN = account_token(api)
        assert api.get("/api/relay/pricing").status_code == 401
        assert api.post("/api/relay/pricing/convert", json={"cnyPer1M": 1}).status_code == 401
        # 写接口用一个**合法**的 body 来测鉴权：body 不合法时 Pydantic 会先挡成 400
        # （那发生在进函数之前，所以测不到 401）。
        assert api.put("/api/relay/pricing/DEEPSEEK/m", json={
            "effectiveFrom": "2026-09-01", "rateVersion": "v", "inputMicrosPer1M": 1,
            "cacheReadMicrosPer1M": 0, "cacheWriteMicrosPer1M": 0,
            "outputMicrosPer1M": 1}).status_code == 401
