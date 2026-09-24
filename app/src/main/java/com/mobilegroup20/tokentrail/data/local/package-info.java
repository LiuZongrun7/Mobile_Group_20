/**
 * 本地数据库（Room）。<b>负责人：张莉。</b>
 *
 * <p>只放三张表，不做 Firestore 的全量镜像：
 * <ol>
 *   <li><b>usage_call</b> —— 导入的原始调用记录。<b>id 上必须建唯一索引</b>，
 *       插入用 {@code OnConflictStrategy.IGNORE}：返回 -1 就是重复，去重靠这一条，
 *       不要另写比对逻辑，也不要为此消耗 Firestore 的读写次数。</li>
 *   <li><b>daily_usage</b> —— 按 (uid, day, provider, model) 的派生汇总，
 *       主键就是这四个字段。游戏和 dashboard 读它，图省一次网络往返。</li>
 *   <li><b>season_state</b> —— 赛季与余额的<b>显示缓存</b>，权威副本在 Firestore。</li>
 * </ol>
 *
 * <p>索引按 {@code (uid, day)} 和 {@code (uid, provider, model, day)} 建，
 * 这两个覆盖了所有查询。
 *
 * <p>不要在本地存论坛帖子：那是读取多、写入少的公共数据，Firestore 自带的磁盘缓存
 * 已经够用，再存一份只会多一处不一致。
 *
 * @see com.mobilegroup20.tokentrail.data.repository.UsageRepository
 */
package com.mobilegroup20.tokentrail.data.local;
