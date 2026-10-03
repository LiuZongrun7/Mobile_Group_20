package com.mobilegroup20.modelpilot.data.local;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * 本地数据库。**只有三张表**，理由见 {@code data/local/package-info.java}。
 *
 * <p>这个类故意保持得很薄：没有单例、没有 {@code getInstance(Context)}、
 * 没有回调。谁来建它、什么时候建，是 {@code RepositoryProvider} 的事——
 * 数据库连接是资源，把「全项目唯一入口」这件事留给那一个地方，
 * 才不会出现两个地方各建一个连接、互相看不见对方写的数据。
 *
 * <p><b>{@code version} 只在真正改了表结构时才加。</b>加了就必须同时写迁移
 * （{@code Migration}）或者明确用 {@code fallbackToDestructiveMigration}——
 * 后者会把用户的原始记录删光，而这个表是「删了就没了」的唯一事实来源，
 * 所以这里<b>不加</b>破坏性回退：宁可升级时崩一下，也不能静默清库。
 *
 * <p>建表语句导出在 {@code app/schemas/}（{@code room.schemaLocation} 配在
 * {@code app/build.gradle.kts}）。<b>那个目录要跟着提交</b>，它是判断迁移写对没写对的基准。
 */
@Database(
        entities = {
                UsageCallEntity.class,
                DailyUsageEntity.class,
                ProjectEntity.class,
                ChatEntity.class,
                MessageEntity.class,
                MemoryEntity.class
        },
        version = 4,
        exportSchema = true)
@TypeConverters(LocalConverters.class)
public abstract class AppDatabase extends RoomDatabase {

    /** 数据库文件名。放在这里而不是让调用方写字符串，免得两处不一致。 */
    public static final String NAME = "modelpilot.db";

    /**
     * v1 → v2：`usage_call` 多三列，装「来源自带的金额」。
     *
     * <p>来由见 {@link com.mobilegroup20.modelpilot.contract.model.UsageCall#costMicros}：
     * DeepSeek 的用量导出自带金额，而那个金额按我们现有的
     * {@code (天, 提供方, 模型)} 汇总还原不出来（同一天同一种 token 会按峰谷分两行）。
     *
     * <p><b>三列都可空，所以不需要给默认值</b>——老行补上 NULL 的语义正好是
     * 「这条记录没有自带金额」，和新增字段的含义一致。要是加成 NOT NULL，
     * 就得给老行编一个 0，那等于告诉滚汇总「这笔是 0 元」，是错的。
     *
     * <p>这是本项目的第一个迁移。它有一个专门的测试盯着：
     * {@code AppDatabaseMigrationTest}（在 androidTest 里，要真机/模拟器）。
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE usage_call ADD COLUMN costMicros INTEGER");
            db.execSQL("ALTER TABLE usage_call ADD COLUMN costCurrency TEXT");
            db.execSQL("ALTER TABLE usage_call ADD COLUMN nativeCostMicros INTEGER");
        }
    };

    /**
     * v2 → v3：**删掉 `season_state` 表**。
     *
     * <p>2026-09-30 产品方向改成 ModelPilot，塔防游戏整块移出本工程，赛季/资源那一套
     * 跟着客户端一起砍了（服务端的 `/season*` 还在，只是没人调）。这张表原来存的是
     * 「本地演示模式下的资源快照」，删了就是删了——它不是用户记录的唯一事实来源，
     * 真正的用量在 `usage_call` / `daily_usage` 两张表里，一条都不动。
     *
     * <p>用 {@code DROP TABLE IF EXISTS}：老库（v2）里有这张表，新装（v3）本来就没有，
     * 加 {@code IF EXISTS} 让两条路都能过。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS season_state");
        }
    };

    /**
     * v3 → v4：**对话那四张表**（项目 / 对话 / 消息 / 记忆）。
     *
     * <p>2026-09-30 起"除论坛外全在手机上"：对话、上下文、记忆、用量都落在本机，
     * 服务端一张表都不加。四张表都是新建的，老库里的用量数据一条不动。
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `project` ("
                    + "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `instructions` TEXT NOT NULL,"
                    + "`color_index` INTEGER NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL,"
                    + "`updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_created_at_epoch_millis`"
                    + " ON `project` (`created_at_epoch_millis`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat` ("
                    + "`id` TEXT NOT NULL, `project_id` TEXT NOT NULL, `title` TEXT NOT NULL,"
                    + "`last_provider_id` TEXT, `last_model_id` TEXT,"
                    + "`created_at_epoch_millis` INTEGER NOT NULL,"
                    + "`updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_project_id` ON `chat` (`project_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_updated_at_epoch_millis`"
                    + " ON `chat` (`updated_at_epoch_millis`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `message` ("
                    + "`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `role` TEXT NOT NULL,"
                    + "`text` TEXT NOT NULL, `attachments_json` TEXT, `tool_calls_json` TEXT,"
                    + "`tool_call_id` TEXT, `tokens_in` INTEGER NOT NULL,"
                    + "`tokens_out` INTEGER NOT NULL, `provider_id` TEXT, `model_id` TEXT,"
                    + "`route` TEXT, `created_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_chat_id_created_at_epoch_millis`"
                    + " ON `message` (`chat_id`, `created_at_epoch_millis`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory` ("
                    + "`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL,"
                    + "`from_message_id` TEXT NOT NULL, `to_message_id` TEXT NOT NULL,"
                    + "`summary` TEXT NOT NULL, `made_by_provider` TEXT NOT NULL,"
                    + "`made_by_model` TEXT NOT NULL, `tokens_in` INTEGER NOT NULL,"
                    + "`tokens_out` INTEGER NOT NULL, `edited_by_user` INTEGER NOT NULL,"
                    + "`created_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chat_id_created_at_epoch_millis`"
                    + " ON `memory` (`chat_id`, `created_at_epoch_millis`)");
            // 账本多三列：Auto 还是手动、属于哪条对话、跑了哪些工具。
            // **都可空**：导入的记录没有这些概念，NULL 的语义正好是"不知道"；
            // 加成 NOT NULL 就得给老行编一个值（编 MANUAL 会把"不知道"说成"手动选的"）。
            db.execSQL("ALTER TABLE usage_call ADD COLUMN route TEXT");
            db.execSQL("ALTER TABLE usage_call ADD COLUMN rate_version TEXT");
            db.execSQL("ALTER TABLE usage_call ADD COLUMN chat_id TEXT");
            db.execSQL("ALTER TABLE usage_call ADD COLUMN tool_calls TEXT");
        }
    };

    public abstract UsageCallDao usageCallDao();

    public abstract DailyUsageDao dailyUsageDao();

    public abstract com.mobilegroup20.modelpilot.chat.local.ChatDao chatDao();
}
