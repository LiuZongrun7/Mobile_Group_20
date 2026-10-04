package com.mobilegroup20.modelpilot.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 迁移测试：<b>唯一能证明 v1 的老库升到 v2 之后还能用</b>的东西。
 *
 * <p>为什么非要有。{@code AppDatabase} 没开 {@code fallbackToDestructiveMigration}，
 * 所以迁移写错的后果不是「数据没了但能用」，而是<b>装了新版的设备直接打不开数据库</b>——
 * 用户看到的是崩溃，而崩溃点和写错的那行 SQL 隔着好几层。单元测试抓不到它：
 * 那是个真的 SQLite 文件，得在设备上建。
 *
 * <p>它做的事：拿 {@code app/schemas/} 里导出的 v1 建表语句建一个库、灌一行老数据、
 * 跑 {@link AppDatabase#MIGRATION_1_2}、然后让 Room 拿 v2 的期望结构逐列比对实际结构。
 * <b>比对本省发生在 {@code runMigrationsAndValidate} 里</b>——所以就算下面一条断言都不写，
 * 「ALTER TABLE 加错了类型」这种错也逃不掉。下面的断言管的是另一半：结构对了，
 * 老数据还在不在。
 *
 * <p>迁移本身改了什么见 {@link AppDatabase#MIGRATION_1_2}。
 */
@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {

    private static final String TEST_DB = "migration-test.db";

    private static final String UID = "uid-1";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    /**
     * v1 的老行升到 v2 之后：老列一个不少，新列是 NULL。
     *
     * <p><b>新列必须是 NULL，不能是 0。</b>加列时要是写成 NOT NULL DEFAULT 0，
     * 每一行老数据都会声称「这笔花了 0 元」，而那是假的——真实含义是「这条记录
     * 没有自带金额，请按价目表算」。这个区别在界面上看不出来（都显示 0），
     * 所以只能在这里盯住。
     */
    @Test
    public void oldRowsKeepTheirTokensAndGetNullCost() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);
        db.execSQL("INSERT INTO usage_call "
                        + "(id, uid, provider, model, startedAtEpochMillis, day, "
                        + " input, cacheRead, cacheWrite, output, source) "
                        + "VALUES ('call:openai:req_1:2026-09-26', ?, 'OPENAI', 'gpt-5', "
                        + " 1758844800000, '2026-09-26', 100, 10, 1, 50, 'IMPORTED')",
                new Object[]{UID});
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);

        try (Cursor c = db.query("SELECT uid, provider, model, input, cacheRead, cacheWrite, "
                + "output, source, costMicros, costCurrency, nativeCostMicros "
                + "FROM usage_call WHERE id = 'call:openai:req_1:2026-09-26'")) {
            assertEquals("老数据必须还在——迁移是加列，不是重建表", 1, c.getCount());
            assertTrue(c.moveToFirst());

            assertEquals(UID, c.getString(0));
            assertEquals("OPENAI", c.getString(1));
            assertEquals("gpt-5", c.getString(2));
            assertEquals(100L, c.getLong(3));
            assertEquals(10L, c.getLong(4));
            assertEquals(1L, c.getLong(5));
            assertEquals(50L, c.getLong(6));
            assertEquals("IMPORTED", c.getString(7));

            assertTrue("老行没有自带金额，必须是 NULL 而不是 0", c.isNull(8));
            assertTrue(c.isNull(9));
            assertTrue(c.isNull(10));
        }
    }

    /** v2 的库能收下带金额的新行，新列也读得回来。 */
    @Test
    public void newRowsCanCarryASourceProvidedCost() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);
        db.close();
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);

        db.execSQL("INSERT INTO usage_call "
                        + "(id, uid, provider, model, startedAtEpochMillis, day, "
                        + " input, cacheRead, cacheWrite, output, source, "
                        + " costMicros, costCurrency, nativeCostMicros) "
                        + "VALUES ('bucket:deepseek:1758816000000:deepseek-chat:key-a', ?, "
                        + " 'DEEPSEEK', 'deepseek-chat', 1758844800000, '2026-09-26', "
                        + " 2000000, 500000, 0, 300000, 'IMPORTED', 492957, 'CNY', 3500000)",
                new Object[]{UID});

        try (Cursor c = db.query("SELECT costMicros, costCurrency, nativeCostMicros "
                + "FROM usage_call WHERE provider = 'DEEPSEEK'")) {
            assertTrue(c.moveToFirst());
            assertEquals(492957L, c.getLong(0));
            assertEquals("CNY", c.getString(1));
            assertEquals("原始金额留着，对账时要和账单逐行对", 3_500_000L, c.getLong(2));
        }
    }

    /**
     * 去重那条验收标准的底座：{@code id} 还是主键。
     *
     * <p>ALTER TABLE 理论上碰不到主键，但「同一份数据导两次不新增」是接口契约里
     * 写死的验收项，而它完全靠这一个约束撑着——迁移改成「建新表 + 拷数据」的写法时
     * 最容易把主键漏掉，所以顺手验一下。
     */
    @Test
    public void idIsStillThePrimaryKeyAfterMigrating() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);
        db.close();
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);

        String insert = "INSERT OR IGNORE INTO usage_call "
                + "(id, uid, provider, model, startedAtEpochMillis, day, "
                + " input, cacheRead, cacheWrite, output, source) "
                + "VALUES ('dup', ?, 'OPENAI', 'gpt-5', 1758844800000, '2026-09-26', "
                + " 1, 0, 0, 1, 'IMPORTED')";
        db.execSQL(insert, new Object[]{UID});
        db.execSQL(insert, new Object[]{UID});

        try (Cursor c = db.query("SELECT COUNT(*) FROM usage_call WHERE id = 'dup'")) {
            assertTrue(c.moveToFirst());
            assertEquals("同一个 id 插两次只该留一条", 1L, c.getLong(0));
        }
    }

    /**
     * v3 → v4：对话那四张表建出来，账本多三列，**老用量一条不动**。
     *
     * <p>2026-09-30 起"除论坛外全在手机上"：对话/记忆/用量都落在本机。
     * 这一版是**纯新增**（四张新表 + 三列可空），所以老行必须原样还在——
     * 迁移里唯一会踩的坑就是给可空列编了默认值（编 `MANUAL` 会把"不知道"
     * 说成"用户手动选的"，那是把不确定当事实）。
     */
    @Test
    public void v3ToV4AddsChatTablesAndKeepsOldUsageRows() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);
        // **列名照 `app/schemas/.../3.json` 抄**：`startedAtEpochMillis` / `cacheRead` /
        // `cacheWrite` 在实体里没写 `@ColumnInfo`，Room 用的就是字段名本身（不是下划线式）。
        // 写成下划线式的后果是这一条 INSERT 自己抛 "no column named …"，
        // 于是**测试根本没验到迁移**——2026-10-04 在真机上跑这一组才发现的。
        db.execSQL("INSERT INTO usage_call(id, uid, provider, model, startedAtEpochMillis, day,"
                + " input, cacheRead, cacheWrite, output, source)"
                + " VALUES('call:old', 'u_1', 'OPENAI', 'gpt-5', 1, '2026-09-27', 10, 0, 0, 5,"
                + " 'IMPORTED')");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4);

        // 新的三列在，而且老行里是 NULL（"不知道"，不是被编出来的值）
        try (android.database.Cursor cursor = db.query(
                "SELECT route, chat_id, tool_calls, input FROM usage_call WHERE id='call:old'")) {
            assertTrue(cursor.moveToFirst());
            assertTrue("route 必须是 NULL，不能编成 MANUAL", cursor.isNull(0));
            assertTrue("chat_id 必须是 NULL", cursor.isNull(1));
            assertTrue("tool_calls 必须是 NULL", cursor.isNull(2));
            assertEquals(10, cursor.getInt(3));
        }
        // 四张新表都在
        for (String table : new String[]{"project", "chat", "message", "memory"}) {
            try (android.database.Cursor cursor = db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
                assertTrue(table + " 没建出来", cursor.moveToFirst());
            }
        }
    }

    /**
     * v4 → v5：账本多两列（任务号 + 调用角色），**老用量一条不动**。
     *
     * <p>起因是真机上跑通压缩之后看出来的：一轮提问其实花了两笔（一次摘要 + 一次回答），
     * 而账本里只有一笔、两笔之间也没有东西串起来。
     *
     * <p>和上一版一样是纯新增的两列可空列，所以老行必须原样还在、且这两列是 NULL——
     * 给 `kind` 编一个 `ANSWER` 等于把"不知道这是什么调用"说成"这是回答"。
     */
    @Test
    public void v4ToV5AddsTaskAndKindAndKeepsOldUsageRows() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 4);
        db.execSQL("INSERT INTO usage_call(id, uid, provider, model, startedAtEpochMillis, day,"
                + " input, cacheRead, cacheWrite, output, source, route, chat_id)"
                + " VALUES('call:v4', 'local', 'DEEPSEEK', 'deepseek-chat', 1, '2026-10-04',"
                + " 100, 0, 0, 20, 'APP', 'AUTO', 'chat-1')");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5);

        try (android.database.Cursor cursor = db.query(
                "SELECT task_id, kind, input, route FROM usage_call WHERE id='call:v4'")) {
            assertTrue(cursor.moveToFirst());
            assertTrue("task_id 必须是 NULL，不能编一个任务号", cursor.isNull(0));
            assertTrue("kind 必须是 NULL，不能编成 ANSWER", cursor.isNull(1));
            assertEquals("老行的用量不能被迁移改动", 100, cursor.getInt(2));
            assertEquals("AUTO", cursor.getString(3));
        }
    }
}
