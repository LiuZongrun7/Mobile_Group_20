package com.mobilegroup20.tokentrail.data.local;

import androidx.room.TypeConverter;

import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.UsageCall;

/**
 * 枚举 ↔ 文本列。
 *
 * <p><b>存 {@code name()} 而不是 {@code ordinal()}。</b>序号会随着枚举里插入一个值
 * 就整体错位——比如以后在 {@code Provider} 中间加一家，昨天存的「1」今天就变成了
 * 另一家，而且没有任何报错。文本列没这个问题，出问题时打开数据库直接看得懂。
 *
 * <p>读的时候不做「查不到就返回默认值」的兜底：数据库里的值都是我们自己写进去的，
 * 出现不认识的值说明库被改过或者迁移写错了，<b>这时候静默兜底会把错的数据算进账单</b>，
 * 不如直接抛出来。真需要容忍脏数据的地方是日志解析，不是这里
 * （见 {@link UsageCallEntity#fromModel} 的注释）。
 */
public final class LocalConverters {

    private LocalConverters() {
    }

    @TypeConverter
    public static String fromProvider(Provider provider) {
        return provider == null ? null : provider.name();
    }

    @TypeConverter
    public static Provider toProvider(String name) {
        return name == null ? null : Provider.valueOf(name);
    }

    @TypeConverter
    public static String fromSource(UsageCall.Source source) {
        return source == null ? null : source.name();
    }

    @TypeConverter
    public static UsageCall.Source toSource(String name) {
        return name == null ? null : UsageCall.Source.valueOf(name);
    }
}
