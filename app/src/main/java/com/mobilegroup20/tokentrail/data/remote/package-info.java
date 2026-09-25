/**
 * 远端数据源：Firestore 和 HTTP。<b>负责人：张莉（用量）、汪庭栋（论坛）、
 * 刘宗润（建议 agent 服务的调用）。</b>
 *
 * <p>Firestore 路径的常量集中在 {@code FirestorePaths}，不要在各处手拼字符串——
 * 路径写错是权限问题最常见的来源，集中一处才好审。
 *
 * <p><b>两类数据的可见性完全不同，集合必须分开：</b>
 * <ul>
 *   <li>用量、预算、赛季状态 —— <b>per-uid 私有</b>。安全规则里每个读写都要
 *       落到 {@code request.auth.uid == uid}；服务端读的是客户端写不进的那部分。</li>
 *   <li>论坛帖子与回复 —— <b>跨账号公共可读</b>，写受限于作者身份。
 *       别把两者塞进同一个集合，否则规则写不出来。</li>
 * </ul>
 *
 * <p>本包还提供 {@code AdviceApi}（Retrofit 接口）指向 Java 建议服务。
 * 客户端<b>不持有建议服务调模型用的那个 key</b>，它在服务端；服务端先验 Firebase ID token，
 * 再从 token 里取 uid，提交的 uid 参数一律不信任。
 *
 * <p><b>另一类别搞混：</b>用量 fetcher 会用到<b>用户自己的</b>凭据（换他账号的用量数据），
 * 那类凭据落客户端、由用户填，和建议服务的 key 是两回事——上面那句不覆盖它，
 * 也不能因为有了它就删掉上面那句。两类 key 的存法与风险见
 * {@code docs/DATA_SOURCES.md} §3。
 *
 * @see com.mobilegroup20.tokentrail.contract.tool.AgentTool
 */
package com.mobilegroup20.tokentrail.data.remote;
