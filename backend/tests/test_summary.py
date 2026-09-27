"""用量汇总（agent 的 `getUsageSummary`）的测试。

重点是 `CONTRACTS.md` §6 那两条机制保证：

1. **`Coverage.daysMissing` 只说「没有记录」的天**——它是「agent 承认自己不知道」
   的机制保证，不靠提示词祈祷模型老实。
2. **`uid` 不是参数**，由服务端从凭据解出来。
"""
import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.pricing import cny_per_1m_to_usd_micros
from tokentrail_forum.relay_store import key_hash
from test_relay import (RELAY_KEY, account_login, account_token, completion, enroll,
                        insert_usage, cst_millis)
from test_seasons import make_season_app

OTHER_USER = "tt_second_user_key_0123456789"


def summary(api, relay_key=RELAY_KEY, **query):
    from urllib.parse import urlencode
    return api.get("/api/relay/usage/summary?" + urlencode(query),
                   headers={"Authorization": "Bearer " + relay_key})


def priced_app(path, seed=True):
    from tokentrail_forum.app import Settings, create_app
    from conftest import verifier
    from test_seasons import TODAY
    settings = Settings(str(path), "https://forum.example", relay_enabled=True,
                        relay_allow_private=True, relay_self_hosts=(), pricing_seed=seed)
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    return create_app(settings, verifier(), client, day_provider=lambda: TODAY)


# ---- Coverage：这条是重点 ----------------------------------------------

def test_missing_days_are_days_without_records(tmp_path):
    """只有「一条都没记」的天算缺。有记录的天（哪怕量很小）不算缺。"""
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK")
        insert_usage(tmp_path, digest, cst_millis("2026-09-22", 12), "DEEPSEEK")

        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-23"}).json()
        coverage = body["coverage"]
        assert coverage["from"] == "2026-09-20" and coverage["to"] == "2026-09-23"
        assert coverage["daysWithData"] == 2
        # 21 和 23 没有记录；20 和 22 有（虽然量很小）——**不因为量小就算缺**
        assert coverage["daysMissing"] == ["2026-09-21", "2026-09-23"]


def test_a_range_with_no_records_at_all_is_entirely_missing(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        body = summary(api, **{"from": "2026-09-01", "to": "2026-09-03"}).json()
        # 「没有记录」不等于「没花钱」——三条全进 daysMissing。
        assert body["coverage"]["daysMissing"] == ["2026-09-01", "2026-09-02", "2026-09-03"]
        assert body["coverage"]["daysWithData"] == 0
        assert body["totals"]["input"] == 0


def test_one_day_range_works(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20"}).json()
        assert body["coverage"]["daysMissing"] == ["2026-09-20"]


# ---- 分组 ---------------------------------------------------------------

def test_grouping_by_day(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK", input_tokens=100, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-21", 12), "DEEPSEEK", input_tokens=200, output=0)
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-21", "groupBy": "DAY"}).json()
        assert body["groupBy"] == "DAY"
        assert {row["key"] for row in body["rows"]} == {"2026-09-20", "2026-09-21"}
        assert body["totals"]["input"] == 300


def test_grouping_by_model_splits_models(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     model="deepseek-chat", input_tokens=100, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     model="deepseek-reasoner", input_tokens=200, output=0)
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20", "groupBy": "MODEL"}).json()
        assert len(body["rows"]) == 2
        assert {row["model"] for row in body["rows"]} == {"deepseek-chat", "deepseek-reasoner"}
        assert body["totals"]["input"] == 300


def test_grouping_by_provider_merges_its_models(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     model="a", input_tokens=100, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     model="b", input_tokens=200, output=0)
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20", "groupBy": "PROVIDER"}).json()
        assert len(body["rows"]) == 1
        assert body["rows"][0]["key"] == "DEEPSEEK"
        assert body["rows"][0]["tokens"]["input"] == 300


def test_an_unknown_provider_groups_under_a_key_but_keeps_provider_null(tmp_path):
    """`provider` 是枚举，值域只有三个。**分组键**可以是 "unknown"，
    但 `provider` 字段必须是 null——塞枚举外的值客户端反序列化会炸。"""
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-20", 12), None, output=0)
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20", "groupBy": "PROVIDER"}).json()
        assert body["rows"][0]["key"] == "unknown"
        assert body["rows"][0]["provider"] is None


def test_an_unknown_group_by_is_rejected(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert summary(api, **{"from": "2026-09-20", "to": "2026-09-20",
                               "groupBy": "NONSENSE"}).status_code == 400


# ---- 成本与「算不出来」 --------------------------------------------------

def test_cost_is_summed_from_the_priced_rows(tmp_path):
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        body = summary(api, **{"from": "2026-09-25", "to": "2026-09-25"}).json()
        assert body["costMicros"] == cny_per_1m_to_usd_micros(1)
        assert body["pricingComplete"] is True
        assert body["rateVersions"] == ["2026-09-deepseek-offpeak"]


def test_an_unpriced_row_is_not_counted_as_zero(tmp_path):
    """算不出价的行**不加进总额**，并且显式告诉调用方这个数不完整。

    加成 0 会得到一个偏低的总额，而且看不出偏低——agent 就会把这个数
    当确切答案讲出来。
    """
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "OPENAI",
                     model="gpt-5", input_tokens=9_000_000, output=0)
        body = summary(api, **{"from": "2026-09-25", "to": "2026-09-25"}).json()
        assert body["costMicros"] == cny_per_1m_to_usd_micros(1)   # 只有有价那笔
        assert body["pricingComplete"] is False
        assert body["unpricedModels"] == ["gpt-5"]
        # totals 里 token 数照旧全算——token 不需要价格就知道
        assert body["totals"]["input"] == 10_000_000


def test_without_any_pricing_the_cost_is_zero_but_marked_incomplete(tmp_path):
    with TestClient(priced_app(tmp_path, seed=False)) as api:
        enroll(api, relay_key=RELAY_KEY)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-25", 12),
                     "DEEPSEEK", input_tokens=1_000_000, output=0)
        body = summary(api, **{"from": "2026-09-25", "to": "2026-09-25"}).json()
        # `pricingComplete` 是调用方唯一能分辨「真的是 0」和「没价」的依据。
        assert body["costMicros"] == 0
        assert body["pricingComplete"] is False
        assert body["rateVersions"] == []


# ---- 参数校验与隔离 ------------------------------------------------------

@pytest.mark.parametrize("bad", [("2026-9-1", "2026-09-02"), ("20260901", "2026-09-02"),
                                 ("2026-13-01", "2026-09-02"), ("2026-02-30", "2026-09-02")])
def test_bad_from_dates_are_400_not_500(tmp_path, bad):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert summary(api, **{"from": bad[0], "to": bad[1]}).status_code == 400


def test_a_reversed_range_is_rejected_not_silently_empty(tmp_path):
    """反向区间**返回 400**，不返回空。

    静默的空区间会让 agent 说「这段时间没有用量」，而实际上它把参数写反了——
    那正是 `Coverage` 那段注释在防的那类错。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert summary(api, **{"from": "2026-09-20", "to": "2026-09-10"}).status_code == 400


def test_an_overlong_range_is_rejected(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert summary(api, **{"from": "2020-01-01", "to": "2026-09-20"}).status_code == 400


def test_the_summary_never_crosses_accounts(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        enroll(api, relay_key=OTHER_USER, upstream_key="sk-2",
               account=account_token(api, "second"))
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-20", 12),
                     "DEEPSEEK", input_tokens=100, output=0)
        insert_usage(tmp_path, account_login(api, "second")["userId"], cst_millis("2026-09-20", 12),
                     "DEEPSEEK", input_tokens=999_999, output=0)
        mine = summary(api, **{"from": "2026-09-20", "to": "2026-09-20"}).json()
        theirs = summary(api, relay_key=OTHER_USER,
                         **{"from": "2026-09-20", "to": "2026-09-20"}).json()
        assert mine["totals"]["input"] == 100
        assert theirs["totals"]["input"] == 999_999
        assert mine["uid"] == account_login(api)["userId"]


def test_the_summary_requires_a_relay_key(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert api.get("/api/relay/usage/summary?from=2026-09-01&to=2026-09-02").status_code == 401
        assert summary(api, "team-token", **{"from": "2026-09-01", "to": "2026-09-02"}).status_code == 401


def test_the_uid_cannot_be_passed_as_a_parameter(tmp_path):
    """`uid` 不是参数——传了也不影响算谁的账（`CONTRACTS.md` §6）。"""
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-20", 12),
                     "DEEPSEEK", input_tokens=42, output=0)
        # 试图冒充别人：多传一个 uid 参数
        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20",
                               "uid": "someone-else"}).json()
        # 算的还是自己的账
        assert body["uid"] == account_login(api)["userId"]
        assert body["totals"]["input"] == 42


# ---- 天界的方向（**这个 bug 真的发生过两次**）-----------------------------

def test_usage_just_after_midnight_belongs_to_that_day(tmp_path):
    """北京 09-20 凌晨 4 点的用量算 09-20，不算 09-19、也不能被丢掉。

    这里踩过两次：

    1. `day_millis_range` 和 SQL 分组用了**同一个正偏移**，等于加了两次，
       整个 09-20 都被跳过；
    2. 改成「减偏移」之后还是差 8 小时——北京 00:00 是 UTC **前一天** 16:00，
       所以起点要比 UTC 的当天 00:00 还早 8 小时。用 `+偏移` 或只减一半，
       北京凌晨 0–8 点的用量就会落在区间之外。

    **为什么原来没发现**：其它用例都用 12:00，那时候怎么错都在窗口里。
    所以这条专门用凌晨的时刻。
    """
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 0, 30), "DEEPSEEK", input_tokens=7, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 4, 0), "DEEPSEEK", input_tokens=11, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-20", 23, 30), "DEEPSEEK", input_tokens=13, output=0)

        body = summary(api, **{"from": "2026-09-20", "to": "2026-09-20"}).json()
        assert body["totals"]["input"] == 31, body
        assert body["coverage"]["daysMissing"] == [], "那一整天都有记录，不该算缺"
        assert body["coverage"]["daysWithData"] == 1


def test_usage_at_the_next_midnight_is_not_in_the_previous_day(tmp_path):
    """北京 09-21 00:00 属于 09-21，不属于 09-20。区间右端是开的。"""
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-21", 0, 0), "DEEPSEEK", input_tokens=99, output=0)
        before = summary(api, **{"from": "2026-09-20", "to": "2026-09-20"}).json()
        after = summary(api, **{"from": "2026-09-21", "to": "2026-09-21"}).json()
        assert before["totals"]["input"] == 0
        assert after["totals"]["input"] == 99


# ---- 比价（agent 的 compareAgentCosts）----------------------------------

def compare(api, relay_key=RELAY_KEY, **query):
    from urllib.parse import urlencode
    return api.get("/api/relay/usage/compare?" + urlencode(query),
                   headers={"Authorization": "Bearer " + relay_key})


def test_compare_rows_carry_all_three_numbers(tmp_path):
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     model="deepseek-chat", input_tokens=1_000_000, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     model="deepseek-reasoner", input_tokens=2_000_000, output=0)
        body = compare(api, **{"from": "2026-09-25", "to": "2026-09-25"}).json()
        assert body["metric"] == "COST"
        assert len(body["rows"]) == 2
        row = body["rows"][0]
        # 三个数都要有，这样 agent 一个响应能答「哪个贵」和「哪个单价高」。
        assert row["costMicros"] > 0
        assert row["tokens"]["input"] > 0
        assert row["costPer1MTokensMicros"] > 0
        # 单价 = 成本 * 1e6 / 总 token
        total = sum(row["tokens"].values())
        assert row["costPer1MTokensMicros"] == row["costMicros"] * 1_000_000 // total


def test_compare_sorts_by_the_requested_metric(tmp_path):
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        # 用**种子里真实存在的模型名**：编一个 `a`/`b` 的话两行都没价、
        # 成本都是 0，排序就测不出来了（这个坑踩过）。
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     model="deepseek-chat", input_tokens=1_000_000, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     model="deepseek-reasoner", input_tokens=100_000_000, output=0)
        by_cost = compare(api, **{"from": "2026-09-25", "to": "2026-09-25",
                                  "metric": "COST"}).json()
        by_tokens = compare(api, **{"from": "2026-09-25", "to": "2026-09-25",
                                    "metric": "TOKENS"}).json()
        assert by_cost["rows"][0]["model"] == "deepseek-reasoner"    # 总成本高的在前
        assert by_tokens["rows"][0]["model"] == "deepseek-reasoner"  # token 多的也在前
        # 两行单价相同（同一个 provider 的同一档费率），所以按名字兜底排序
        by_unit = compare(api, **{"from": "2026-09-25", "to": "2026-09-25",
                                  "metric": "COST_PER_1M"}).json()
        assert all(row["costPer1MTokensMicros"] > 0 for row in by_unit["rows"])


def test_compare_keeps_unpriced_rows_out_of_the_ranking(tmp_path):
    """算不出价的行**不参与排序**（成本是 0 会把它排到最后，看起来像「便宜」），
    但 `pricingComplete` 和 `unpricedModels` 会说明这件事。
    """
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     model="deepseek-chat", input_tokens=1_000_000, output=0)
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "OPENAI",
                     model="gpt-5", input_tokens=1_000_000, output=0)
        body = compare(api, **{"from": "2026-09-25", "to": "2026-09-25"}).json()
        assert body["pricingComplete"] is False
        assert body["unpricedModels"] == ["gpt-5"]
        # **有价那行排前面**：没价那行的 costMicros 是 0，但那个 0 是
        # 「不知道」而不是「最便宜」——让它排到第一就等于把「不知道」当结论讲出来。
        assert body["rows"][0]["model"] == "deepseek-chat"
        assert body["rows"][0]["priced"] is True
        assert body["rows"][1]["model"] == "gpt-5"
        assert body["rows"][1]["priced"] is False


def test_compare_carries_coverage_like_the_summary(tmp_path):
    with TestClient(priced_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-25", 12),
                     "DEEPSEEK", input_tokens=1_000_000, output=0)
        body = compare(api, **{"from": "2026-09-24", "to": "2026-09-26"}).json()
        # 和汇总同一个口径：只有「一条记录都没有」的天算缺
        assert body["coverage"]["daysMissing"] == ["2026-09-24", "2026-09-26"]
        assert body["coverage"]["daysWithData"] == 1


def test_compare_rejects_unknown_metrics_and_reversed_ranges(tmp_path):
    with TestClient(make_season_app(tmp_path)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert compare(api, **{"from": "2026-09-20", "to": "2026-09-20",
                               "metric": "SMARTNESS"}).status_code == 400
        assert compare(api, **{"from": "2026-09-20", "to": "2026-09-01"}).status_code == 400
        assert compare(api, "team-token", **{"from": "2026-09-20",
                                             "to": "2026-09-20"}).status_code == 401
