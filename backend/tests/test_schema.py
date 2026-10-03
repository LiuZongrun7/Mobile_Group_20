"""库的形状：老库里的废弃表要删掉，`usage.py` 的四桶归一化不能坏。

2026-09-30 那一轮收缩把用量、预算、价目、赛季四组接口与表全删了，服务端只剩
账号、论坛/新闻、以及智能体自己那本账（`agent_usage`）。这个文件守着两件事：

1. **老库升级**：生产库上那些表是真实存在过的（`relay_secrets` 里甚至有用户上游
   key 的明文），`CREATE TABLE IF NOT EXISTS` 不会删表，所以 `schema.drop_obsolete_tables`
   必须在每次启动时把它们清掉。**这条不能只靠"新装的库是干净的"**——
   服务器上的库是升级上来的。
2. **`split_usage` 的两种字段形状**：智能体调完模型要把它归一成四个桶，
   这是 `usage.py` 里唯一还活着的函数。
"""
from fastapi.testclient import TestClient

from modelpilot_forum.app import Settings, create_app
from modelpilot_forum.schema import OBSOLETE_TABLES, day_millis_range, month_bounds
from modelpilot_forum.store import Store
from modelpilot_forum.usage import split_usage
from conftest import verifier
from helpers import account_token, bearer


def test_split_usage_handles_both_field_shapes():
    """`input` 只装**未命中缓存**的部分（`CONTRACTS.md` §8）。

    两种字段形状都要认：OpenAI/DeepSeek 的 `prompt_tokens` 是输入总量、要把缓存减掉，
    而 Anthropic 的 `input_tokens` 本身就已经是未命中部分——再减一次会出负数，
    而负数金额不会报错，只会让账对不上。
    """
    openai = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                          "prompt_tokens_details": {"cached_tokens": 30}}, "gpt-5")
    assert (openai["input"], openai["cacheRead"], openai["output"]) == (70, 30, 10)
    deepseek = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                            "prompt_cache_hit_tokens": 40,
                            "prompt_cache_miss_tokens": 60}, "deepseek-chat")
    assert (deepseek["input"], deepseek["cacheRead"]) == (60, 40)
    anthropic = split_usage({"input_tokens": 25, "output_tokens": 5,
                             "cache_creation_input_tokens": 100}, "claude")
    assert (anthropic["input"], anthropic["cacheWrite"], anthropic["output"]) == (25, 100, 5)
    # 没有 usage 的响应返回 None，不是全 0——「没有」和「花了 0」要分得开。
    assert split_usage({}, "m") is None


def test_the_calendar_helpers_agree_with_the_stored_offset():
    """「一天」的两个方向都要对：北京时间 23:30 与次日 00:30 必须落在两天。

    存储是 UTC 毫秒、口径是 `Asia/Shanghai`，两个方向用反了就会让同一天的量
    落到相邻两天上——那个 bug 看起来只是"少了一天"，很难查。
    """
    # `day_millis_range("2026-09-27")` 的起点是**北京 09-27 00:00**（UTC 09-26 16:00），
    # 所以同一天的 23:30 是 +23.5h、次日 00:30 是 +24.5h。
    start, following = day_millis_range("2026-09-27")
    late = start + int(23.5 * 3600) * 1000       # 北京 09-27 23:30
    early = start + int(24.5 * 3600) * 1000      # 北京 09-28 00:30
    assert start <= late < following
    assert early >= following, "次日凌晨必须落在下一天的区间里"
    assert month_bounds("2026-09") == ("2026-09-01", "2026-10-01")
    assert month_bounds("2026-12") == ("2026-12-01", "2027-01-01")


def test_obsolete_tables_are_dropped_from_an_old_database(tmp_path):
    """老库里的七张废弃表要被清掉，**而且里面有数据也要清**。

    最要紧的是 `relay_secrets`：那张表装的是用户上游 API key 的**明文**。
    ``CREATE TABLE IF NOT EXISTS`` 不会动已存在的表，所以只有显式 DROP 才能让它消失。
    """
    import sqlite3

    path = tmp_path / "modelpilot.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_keys (key_hash TEXT PRIMARY KEY, user_id TEXT,
             display_name TEXT NOT NULL, created INTEGER NOT NULL);
            CREATE TABLE relay_secrets (key_hash TEXT PRIMARY KEY, upstream_url TEXT NOT NULL,
             upstream_secret TEXT NOT NULL, created INTEGER NOT NULL);
            CREATE TABLE relay_usage (call_id TEXT, user_id TEXT, model TEXT, created INTEGER);
            CREATE TABLE season_balances (user_id TEXT PRIMARY KEY, updated INTEGER);
            CREATE TABLE budgets (user_id TEXT, month TEXT, cap_micros INTEGER);
            CREATE TABLE pricing_rates (provider TEXT, model TEXT, effective_from TEXT);
        """)
        db.execute("INSERT INTO relay_secrets VALUES('deadbeef','https://api.example','sk-x',1)")

    store = Store(str(tmp_path), "/api")
    with store.connect(write=True) as db:
        from modelpilot_forum.schema import drop_obsolete_tables
        drop_obsolete_tables(db)
        tables = {row["name"] for row in
                  db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert not (set(OBSOLETE_TABLES) & tables), f"没删干净: {set(OBSOLETE_TABLES) & tables}"
    # 幂等：再来一遍不能炸
    with store.connect(write=True) as db:
        from modelpilot_forum.schema import drop_obsolete_tables
        drop_obsolete_tables(db)


def test_starting_the_app_cleans_an_old_database(tmp_path):
    """**启动就要清干净**，不能等谁来调一次清理函数。

    这是这条规矩真正的落点：生产库是升级上来的，服务一起来就该把凭据表删掉。
    """
    import sqlite3

    path = tmp_path / "modelpilot.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_secrets (key_hash TEXT PRIMARY KEY, upstream_url TEXT NOT NULL,
             upstream_secret TEXT NOT NULL, created INTEGER NOT NULL);
        """)
    settings = Settings(str(tmp_path), "https://forum.example")
    with TestClient(create_app(settings, verifier())) as api:
        token = account_token(api)
        assert api.get("/health", headers=bearer(token)).status_code == 200
    with sqlite3.connect(path) as db:
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "relay_secrets" not in tables, tables
