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
 * 客户端<b>不持有任何模型 API key</b>，key 只在服务端；服务端先验 Firebase ID token，
 * 再从 token 里取 uid，提交的 uid 参数一律不信任。
 *
 * @see com.mobilegroup20.tokentrail.contract.tool.AgentTool
 */
package com.mobilegroup20.tokentrail.data.remote;
