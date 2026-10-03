"""服务端结算的测试：规则 1/3/4 和幂等。

`CONTRACTS.md` §7 那五条规则里有三条**没法用真实时钟验证**——造不出「三天前」，
也不该去改系统时间。所以 `create_app` 收一个 `day_provider`，
这些用例注入一个固定的「今天」，于是「今天不结算」「一次补三天」都能真的断言。
"""
import httpx
import pytest
from datetime import date, timedelta
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.seasons import MAX_BACKFILL_DAYS, TOKENS_PER_UNIT, tokens_to_resources
from conftest import verifier
from helpers import (account_login, account_token, bearer, completion,
                      cst_millis, daily, insert_usage)

TODAY = date(2026, 9, 27)


def day(offset):
    """相对固定「今天」的某一天，格式 yyyy-MM-dd。"""
    return (TODAY + timedelta(days=offset)).isoformat()


def make_season_app(path, today=TODAY):
    """开记账接口的 app，「今天」注入成固定值。

    **不再需要假上游**：赛季/预算/汇总都是读库里的用量行，和中转在不在没关系。
    """
    settings = Settings(str(path), "https://forum.example", relay_enabled=True)
    return create_app(settings, verifier(), day_provider=lambda: today)


def season(api, relay_key=None):
    relay_key = relay_key or account_token(api)
    return api.get("/api/relay/season", headers={"Authorization": "Bearer " + relay_key})


def settle(api, relay_key=None):
    relay_key = relay_key or account_token(api)
    return api.post("/api/relay/season/settle", headers={"Authorization": "Bearer " + relay_key})


def spend(api, amounts, relay_key=None):
    relay_key = relay_key or account_token(api)
    return api.post("/api/relay/season/spend", headers={"Authorization": "Bearer " + relay_key},
                    json=amounts)


def usage_on(tmp_path, digest, day_string, input_tokens=0, cache_read=0, cache_write=0,
             output=0, calls=1, provider="DEEPSEEK", model="deepseek-chat", hour=12):
    insert_usage(tmp_path, digest, cst_millis(day_string, hour), provider, model,
                 input_tokens, cache_read, cache_write, output, calls)


# ---- 换算率 -------------------------------------------------------------

def test_one_million_tokens_is_one_hundred_units():
    """`CONTRACTS.md` §8：每 100 万 token 换 100 个资源。

    这条写错过：一开始按 1000 token = 1 单位实现，资源整整多十倍，
    而且界面上看着仍然「像那么回事」，不会报任何错。
    """
    assert TOKENS_PER_UNIT == 10_000
    assert tokens_to_resources(1_000_000, 0, 0, 0)["input"] == 100
    assert tokens_to_resources(9_999, 0, 0, 0)["input"] == 0        # 向下取整，余数丢弃


def test_cache_read_and_write_merge_into_one_resource():
    """两个缓存桶合成一种资源，而且**先加总再取整**。

    分开取整的话，下面第一组 15000 + 15000 会变成 1 + 1 = 2，而正确结果是 3——
    零头被丢了两次。这是「合并只发生在显示层」那条口径在结算里的落点。
    """
    # 15k + 15k = 30k → 3 个单位（分着算会得 1+1=2）
    assert tokens_to_resources(0, 15_000, 15_000, 0)["cache"] == 3
    # 单独看各桶：够 1 个单位就换得出，1 万以下换不出
    assert tokens_to_resources(0, 10_000, 0, 0)["cache"] == 1
    assert tokens_to_resources(0, 0, 10_000, 0)["cache"] == 1
    assert tokens_to_resources(0, 9_999, 0, 0)["cache"] == 0


def test_three_resources_do_not_convert_into_each_other():
    """三种资源互不通兑——否则「多用缓存」就成了刷资源的漏洞。"""
    converted = tokens_to_resources(10_000, 20_000, 0, 30_000)
    assert converted == {"input": 1, "cache": 2, "output": 3}


# ---- 规则 1：今天永远不结算 ----------------------------------------------

def test_today_is_never_settled(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        usage_on(tmp_path, digest, day(0), input_tokens=1_000_000)      # 今天
        usage_on(tmp_path, digest, day(-1), input_tokens=1_000_000)     # 昨天

        result = settle(api).json()
        # 只结算了昨天。今天的用量还在涨，结算了就违反「已结算的天不可变」。
        assert result["settledDays"] == [day(-1)]
        assert result["gained"]["input"] == 100
        assert result["lastSettledDay"] == day(-1)


def test_a_day_with_only_today_usage_settles_nothing(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(0), input_tokens=5_000_000)
        result = settle(api).json()
        assert result["settledDays"] == []
        assert result["balanceAfter"] == {"input": 0, "cache": 0, "output": 0}


# ---- 规则 2：幂等 -------------------------------------------------------

def test_settling_twice_does_not_grant_twice(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000, cache_read=500_000)

        first = settle(api).json()
        assert first["settledDays"] == [day(-1)]
        assert first["balanceAfter"]["input"] == 100

        second = settle(api).json()
        # 第二次没有任何新的一天可结，余额一分不动。
        assert second["settledDays"] == []
        assert second["balanceAfter"] == first["balanceAfter"]
        assert second["gained"] == {"input": 0, "cache": 0, "output": 0}


def test_usage_added_to_an_already_settled_day_does_not_change_history(tmp_path):
    """规则 4：已结算的天不可变。之后落进来的记录不回头改历史。"""
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        usage_on(tmp_path, digest, day(-1), input_tokens=1_000_000)
        before = settle(api).json()["balanceAfter"]

        # 模拟「纠正记录晚到」：又往昨天插了一笔
        usage_on(tmp_path, digest, day(-1), input_tokens=9_000_000, model="deepseek-reasoner")
        after = settle(api).json()

        assert after["settledDays"] == []
        assert after["balanceAfter"] == before


# ---- 规则 3：补齐 -------------------------------------------------------

def test_three_missed_days_are_settled_in_one_call(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        for offset in (-3, -2, -1):
            usage_on(tmp_path, digest, day(offset), input_tokens=100_000)

        result = settle(api).json()
        assert result["settledDays"] == [day(-3), day(-2), day(-1)]
        # 三天各 10 个单位
        assert result["gained"]["input"] == 30
        assert result["lastSettledDay"] == day(-1)


def test_catch_up_converts_per_day_not_per_row(tmp_path):
    """一天的多个 (provider, 模型) 先加总再取整。

    三行各 5000 token：按行取整是 0+0+0 = 0，按天加总是 15000 // 10000 = 1。
    这条和 `test_cache_read_and_write_merge_into_one_resource` 是同一个口径的两面。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        for model in ("a", "b", "c"):
            usage_on(tmp_path, digest, day(-1), input_tokens=5_000, model=model)
        result = settle(api).json()
        assert result["gained"]["input"] == 1


# ---- spend --------------------------------------------------------------

def test_spend_deducts_and_refuses_to_go_negative(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000, output=1_000_000)
        settle(api)

        ok = spend(api, {"input": 40, "output": 60})
        assert ok.status_code == 200
        assert ok.json()["balance"] == {"input": 60, "cache": 0, "output": 40}

        # 超支要被拒，而且**余额一点不动**。
        refused = spend(api, {"input": 61})
        assert refused.status_code == 409
        assert season(api).json()["balance"] == {"input": 60, "cache": 0, "output": 40}


def test_spend_does_not_let_one_resource_cover_another(tmp_path):
    """三种资源互不通兑：input 再多也不能拿来买吃 output 的东西。"""
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=10_000_000)
        settle(api)
        assert season(api).json()["balance"]["input"] == 1000
        # output 余额是 0，要 1 个也不行
        assert spend(api, {"output": 1}).status_code == 409


def test_spend_before_any_settlement_is_refused(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert spend(api, {"input": 1}).status_code == 409


def test_spend_rejects_empty_and_negative_amounts(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000)
        settle(api)
        assert spend(api, {}).status_code == 400                 # 什么都没扣
        assert spend(api, {"input": -5}).status_code == 400      # 负数（等于加资源）


# ---- 状态与流水 ---------------------------------------------------------

def test_season_state_reports_month_tokens_and_rate(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        usage_on(tmp_path, digest, day(-1), input_tokens=1_000_000, cache_read=200_000, output=300_000)

        body = season(api).json()
        assert body["balance"] == {"input": 0, "cache": 0, "output": 0}   # 还没结算
        assert body["tokensPerUnit"] == TOKENS_PER_UNIT
        assert body["monthTokens"]["input"] == 1_000_000
        assert body["monthTokens"]["cacheRead"] == 200_000
        # 服务端自己算「今天」，不信客户端传的月份。
        assert body["monthTokens"]["isCurrentMonth"] is True


def test_settled_days_are_listed_for_audit(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-2), input_tokens=1_000_000)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=2_000_000)
        settle(api)
        items = api.get("/api/relay/season/days",
                        headers={"Authorization": "Bearer " + TOKEN}).json()["items"]
        assert [item["day"] for item in items] == [day(-1), day(-2)]
        assert items[0]["gained"]["input"] == 200
        assert items[1]["gained"]["input"] == 100


# ---- 隔离与鉴权 ---------------------------------------------------------

def test_two_users_settle_separately(tmp_path):
    other = "tt_second_user_key_0123456789"
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        OTHER = account_token(api, "second")
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000)
        usage_on(tmp_path, account_login(api, "second")["userId"], day(-1), input_tokens=5_000_000)

        assert settle(api).json()["balanceAfter"]["input"] == 100
        assert settle(api, OTHER).json()["balanceAfter"]["input"] == 500
        # 各自只看到自己的流水
        mine = api.get("/api/relay/season/days",
                       headers={"Authorization": "Bearer " + TOKEN}).json()["items"]
        assert len(mine) == 1


def test_season_endpoints_require_a_relay_key(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert api.get("/api/relay/season").status_code == 401
        assert api.post("/api/relay/season/settle").status_code == 401
        assert api.post("/api/relay/season/spend", json={"input": 1}).status_code == 401
        for bad in ("team-token", "tt_not_registered_0123456789"):
            headers = {"Authorization": "Bearer " + bad}
            assert api.get("/api/relay/season", headers=headers).status_code == 401
            assert api.post("/api/relay/season/settle", headers=headers).status_code == 401


def test_backfill_is_bounded_for_a_brand_new_account(tmp_path):
    """新账号第一次结算不能把全部历史一次算完。

    `lastSettledDay` 为空时往回无上限的话，一个刚注册的账号会把
    MAX_BACKFILL_DAYS 之前的用量也领出来——那种数据不该存在，
    但真出现时要有边界，否则一次请求就能造出一个巨大的余额。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        usage_on(tmp_path, digest, day(-5), input_tokens=1_000_000)                  # 窗口内
        usage_on(tmp_path, digest, day(-MAX_BACKFILL_DAYS - 10), input_tokens=9_000_000)  # 窗口外

        result = settle(api).json()
        assert result["settledDays"] == [day(-5)]
        assert result["gained"]["input"] == 100


def test_settlement_survives_a_restart(tmp_path):
    """余额在服务端，重启进程不该丢——这正是「以服务端为准」要解决的问题。"""
    # 先起一次、登录账号拿到 userId，才能把用量播到**账号**头上。
    with TestClient(make_season_app(tmp_path)) as first:
        usage_on(tmp_path, account_login(first)["userId"], day(-1), input_tokens=1_000_000)
        settle(first)
    with TestClient(make_season_app(tmp_path)) as restarted:
        body = season(restarted).json()
        assert body["balance"]["input"] == 100
        assert body["lastSettledDay"] == day(-1)
        # 重启后再结算一次也不能重复发
        assert settle(restarted).json()["settledDays"] == []


def test_concurrent_settle_requests_cannot_double_grant(tmp_path):
    """并发调用不能发两次。靠 `BEGIN IMMEDIATE` + `(uid, day)` 主键。"""
    import threading
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000)
        results = []
        threads = [threading.Thread(target=lambda: results.append(settle(api).json()))
                   for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        # 两次里只有一次真的结算了那天
        granted = [entry for entry in results if entry["settledDays"]]
        assert len(granted) == 1
        assert season(api).json()["balance"]["input"] == 100
