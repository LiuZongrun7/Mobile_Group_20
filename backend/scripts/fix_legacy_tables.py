"""一次性维护：把老形状的 `agent_usage` / 没用的 `users` 表处理掉。

## 为什么需要它

`CREATE TABLE IF NOT EXISTS` 对**已经存在**的表什么都不做，改表定义不会生效。
`relay_usage` / `season_balances` / `season_settlements` / `budgets` 的归属列
改名有 `relay_store.migrate_ownership` 兜着，但有两张表不在那条迁移里：

* `agent_usage` 原来是 `key_hash`（relay key 的 sha256）。现在的代码写 `user_id`，
  于是**每一次提问都会在最后一步 INSERT 失败**——而那时候模型已经答完了，
  用户会看到回答，账却没记上。
* `users` 是「relay key ↔ 团队账号绑定」那个方案的遗留。身份统一成账号之后，
  没有两个身份，也就不需要绑定表，代码里已经没有任何地方读写它。

## 两条守卫

都不是形式主义：

1. **只在形状确实不对时动它**（有 `key_hash`、没有 `user_id`）。
2. **只在数据确实没价值时才丢**：`agent_usage` 的每一行都要属于一条
   **不存在的 relay key**（那种行是测试留下的孤儿）；`users` 必须没有任何
   账号引用它的 `team_uid`。有一条对不上就**什么都不做并打印出来**，
   让人工看一眼——这个脚本宁可什么也不干，也不能悄悄删掉真数据。

用法：

    python scripts/fix_legacy_tables.py /var/lib/tokentrail-forum/forum.sqlite3
"""
import sqlite3
import sys


def columns(db, table):
    return [row[1] for row in db.execute(f"PRAGMA table_info({table})")]


def table_exists(db, table):
    return db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                      (table,)).fetchone() is not None


def fix_agent_usage(db):
    """`agent_usage.key_hash` → `user_id`。返回做了什么。"""
    if not table_exists(db, "agent_usage"):
        return "agent_usage 不存在，跳过"
    names = columns(db, "agent_usage")
    if "user_id" in names:
        return "agent_usage 已经是新形状，跳过"
    if "key_hash" not in names:
        return f"agent_usage 形状不认识（{names}），**没动**，请人工确认"
    rows = db.execute("SELECT COUNT(*) FROM agent_usage").fetchone()[0]
    # 唯一的守卫：这些行必须属于**已经不存在的** relay key。
    # 只要有一条指向真 key，就说明它是真实用量，不能丢。
    orphans = db.execute("""SELECT COUNT(*) FROM agent_usage a
        WHERE NOT EXISTS (SELECT 1 FROM relay_keys k WHERE k.key_hash=a.key_hash)""").fetchone()[0]
    if rows != orphans:
        return (f"agent_usage 有 {rows - orphans} 行属于**存在**的 relay key，"
                f"**没动**：那些是真账，请人工决定怎么并到账号上")
    if rows:
        print(f"  丢弃 {rows} 行孤儿早于本次改动的测试账（它们不属于任何已注册的 key）")
    db.execute("DROP TABLE agent_usage")
    return "agent_usage 已重建（旧形状 → `user_id`，下次启动由 agent.py 建好）"


def drop_unused_users(db):
    """`users` 表：绑定方案的遗留。只在确实没人引用时删。"""
    if not table_exists(db, "users"):
        return "users 不存在，跳过"
    if not table_exists(db, "accounts"):
        return "accounts 表还没有，**没动 users**（顺序不对，先让服务起来）"
    reference = db.execute("""SELECT COUNT(*) FROM users u JOIN accounts a
        ON a.user_id = u.team_uid""").fetchone()[0]
    rows = db.execute("SELECT COUNT(*) FROM users").fetchone()[0]
    if reference:
        return f"users 里有 {reference} 行被账号引用，**没动**，请人工确认"
    print(f"  丢弃 users 的 {rows} 行：绑定方案已废弃，代码里没有任何读写")
    db.execute("DROP TABLE users")
    return "users 已删除（relay key ↔ 账号绑定已不存在，没有两个身份就不需要绑定表）"


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    path = argv[1]
    # **先备份再动手。** 这个脚本会 DROP 表，而它跑在生产库上；
    # 备份用 SQLite 自己的 backup API（不是 `cp`）：WAL 模式下直接拷主文件
    # 可能少掉还没 checkpoint 的那部分。
    backup = f"{path}.before-legacy-fix"
    with sqlite3.connect(path) as source, sqlite3.connect(backup) as target:
        source.backup(target)
    print(f"已备份到 {backup}")
    db = sqlite3.connect(path)
    try:
        print(f"维护 {path}")
        print(" ", fix_agent_usage(db))
        print(" ", drop_unused_users(db))
        db.commit()
        print("  剩下这几张表：",
              ", ".join(row[0] for row in db.execute(
                  "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")))
    except Exception:
        # 中途出错就整体回滚，绝不留下「drop 了一半」的库。
        db.rollback()
        raise
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
