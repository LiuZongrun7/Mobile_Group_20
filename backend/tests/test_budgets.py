"""服务端预算的测试。

两个重点：**「没设预算」和「设了 0 元」必须分得开**，以及
**`Coverage.daysMissing` 的口径**——只有「一条记录都没有」的天才算缺，
「用量为 0」不算。
"""
import httpx
import pytest
from datetime import date, timedelta
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.budgets import DEFAULT_WARN_RATIO, month_bounds
from conftest import verifier
from helpers import (account_login, account_token, bearer, completion,
                      cst_millis, daily, insert_usage)
from test_seasons import TODAY, day, make_season_app, usage_on

MONTH = "2026-09"

def budget(api, relay_key=None, month=MONTH):
    relay_key = relay_key or account_token(api)
    return api.get(f"/api/relay/budgets/{month}", headers={"Authorization": "Bearer " + relay_key})


def save(api, cap, ratio=None, relay_key=None, month=MONTH):
    relay_key = relay_key or account_token(api)
    payload = {"capMicros": cap}
    if ratio is not None:
        payload["warnAtRatio"] = ratio
    return api.put(f"/api/relay/budgets/{month}", headers={"Authorization": "Bearer " + relay_key},
                   json=payload)


# ---- 没设 vs 设了 0 -----------------------------------------------------

def test_without_a_budget_configured_is_false_and_cap_is_null(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        body = budget(api).json()
        # 关键：**不是** capMicros == 0。没设预算和设了 0 元预算在界面上
        # 要说两句不同的话（「你还没设预算」/「你已经超了」）。
        assert body["configured"] is False
        assert body["capMicros"] is None
        assert body["warnAtRatio"] is None


def test_a_zero_cap_is_configured_and_distinguishable(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert save(api, 0).status_code == 200
        body = budget(api).json()
        assert body["configured"] is True
        assert body["capMicros"] == 0


def test_saving_twice_overwrites(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        save(api, 20_000_000, 0.5)
        assert budget(api).json()["capMicros"] == 20_000_000
        save(api, 5_000_000)
        body = budget(api).json()
        assert body["capMicros"] == 5_000_000
        # 第二次没传比例，回落默认值——不是把上一次的 0.5 留着。
        assert body["warnAtRatio"] == DEFAULT_WARN_RATIO


@pytest.mark.parametrize("bad", [-1, 10**16])
def test_absurd_caps_are_rejected(tmp_path, bad):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert save(api, bad).status_code == 400


@pytest.mark.parametrize("bad", [1.5, -0.1])
def test_warn_ratio_must_be_a_ratio(tmp_path, bad):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert save(api, 1_000_000, bad).status_code == 400


# ---- 花销算不出来这件事必须显式 --------------------------------

def test_spend_is_reported_as_unavailable_not_as_zero(tmp_path):
    """没有价目表时 `spentMicros` 是 0，但 `pricingAvailable` 是 false。

    客户端**不能**因为看到 0 就说「你花了 0 元」——`CONTRACTS.md` §4 那条底线：
    把「不知道」显示成 0 是「数字看着没错、结论是错的」。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1_000_000)
        body = budget(api).json()
        assert body["pricingAvailable"] is False
        assert body["spentMicros"] == 0


# ---- Coverage 口径 ------------------------------------------------------

def test_days_with_data_are_counted_and_others_are_missing(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        # 本月有记录的两天（今天是 2026-09-27，所以只能算到 27 号）
        usage_on(tmp_path, account_login(api)["userId"], day(-1), input_tokens=1000)
        usage_on(tmp_path, account_login(api)["userId"], day(-2), input_tokens=1000)
        coverage = budget(api).json()["coverage"]

        assert coverage["from"] == "2026-09-01"
        # 本月只算到**今天**——把未来的天算进去会让覆盖度永远填不满，那是假的「缺数据」。
        assert coverage["to"] == "2026-09-27"
        assert coverage["daysWithData"] == 2
        assert len(coverage["daysMissing"]) == 25
        # 有数据的那两天不在缺失列表里
        assert day(-1) not in coverage["daysMissing"]
        assert day(-2) not in coverage["daysMissing"]


def test_a_month_with_no_records_is_entirely_missing(tmp_path):
    """一条记录都没有 = 全都不知道，**不是**「这个月没花钱」。

    这是 `Coverage` 类注释点名的区别：前者进 `daysMissing`，后者不进。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        coverage = budget(api).json()["coverage"]
        assert coverage["daysWithData"] == 0
        assert len(coverage["daysMissing"]) == 27      # 9/1 – 9/27
        assert coverage["daysMissing"][0] == "2026-09-01"


def test_the_current_month_stops_at_today(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        coverage = budget(api).json()["coverage"]
        # 9 月有 30 天，但今天是 27 号 —— 28/29/30 不该出现在缺失里
        for future in ("2026-09-28", "2026-09-29", "2026-09-30"):
            assert future not in coverage["daysMissing"]


def test_a_past_month_counts_its_whole_length(tmp_path):
    """已经过完的月按整月算，不受「今天」影响。"""
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        coverage = api.get("/api/relay/budgets/2026-08",
                           headers={"Authorization": "Bearer " + TOKEN}).json()["coverage"]
        assert coverage["from"] == "2026-08-01"
        assert coverage["to"] == "2026-08-31"          # 8 月有 31 天，全算进去
        assert len(coverage["daysMissing"]) == 31


def test_february_length_is_handled(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        coverage = api.get("/api/relay/budgets/2026-02",
                           headers={"Authorization": "Bearer " + TOKEN}).json()["coverage"]
        assert coverage["to"] == "2026-02-28"          # 2026 不是闰年


def test_month_bounds_rolls_over_the_year():
    assert month_bounds("2026-09") == ("2026-09-01", "2026-10-01")
    assert month_bounds("2026-12") == ("2026-12-01", "2027-01-01")


# ---- 鉴权与隔离 ---------------------------------------------------------

@pytest.mark.parametrize("bad", ["2026", "2026-13", "2026-00", "26-09", "2026-9", "abcd-ef"])
def test_bad_months_are_400_not_500(tmp_path, bad):
    """格式错必须是 400。手写校验而不是直接 `date.fromisoformat`——
    后者会抛内部异常，返回给客户端就成了 500。"""
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert budget(api, month=bad).status_code == 400
        assert save(api, 1000, month=bad).status_code == 400


def test_budgets_are_per_account(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        # 第二个用户要有**自己的账号**：不然两条 key 都挂在同一个账号下面，
        # 「预算按账号隔离」这条就测了个寂寞（会红成 configured=True）。
        OTHER = account_token(api, "second")
        save(api, 20_000_000)
        # 另一个账号没设过，不能看到我的
        theirs = budget(api, relay_key=OTHER).json()
        assert theirs["configured"] is False
        assert theirs["capMicros"] is None
        save(api, 1_000_000, relay_key=OTHER)
        assert budget(api).json()["capMicros"] == 20_000_000
        assert budget(api, relay_key=OTHER).json()["capMicros"] == 1_000_000


def test_a_budget_survives_a_restart(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        save(api, 42_000_000, 0.9)
    with TestClient(make_season_app(tmp_path)) as restarted:
        body = budget(restarted).json()
        assert body["capMicros"] == 42_000_000
        assert body["warnAtRatio"] == 0.9


def test_budget_endpoints_require_a_relay_key(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        assert api.get(f"/api/relay/budgets/{MONTH}").status_code == 401
        assert api.put(f"/api/relay/budgets/{MONTH}", json={"capMicros": 1}).status_code == 401
        for bad in ("team-token", "tt_not_registered_0123456789"):
            headers = {"Authorization": "Bearer " + bad}
            assert api.get(f"/api/relay/budgets/{MONTH}", headers=headers).status_code == 401
            assert api.put(f"/api/relay/budgets/{MONTH}", headers=headers,
                           json={"capMicros": 1}).status_code == 401


def test_unknown_fields_are_rejected(tmp_path):
    """`extra="forbid"`：拼错字段名不会静默忽略。"""
    with TestClient(make_season_app(tmp_path)) as api:
        TOKEN = account_token(api)
        response = api.put(f"/api/relay/budgets/{MONTH}",
                           headers={"Authorization": "Bearer " + TOKEN},
                           json={"capMicros": 1, "capUsd": 5})
        assert response.status_code == 400
