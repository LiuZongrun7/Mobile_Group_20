/**
 * 论坛界面：官方公告、热帖、我的帖子与回复，以及发帖回帖。
 * <b>负责人：论坛侧（汪庭栋）。</b>
 *
 * <p>三个分页对应 {@link com.mobilegroup20.tokentrail.data.repository.ForumRepository}
 * 的三个查询，<b>顺序一律用服务端算好的
 * {@link com.mobilegroup20.tokentrail.contract.model.ForumPost#rankScore}</b>，
 * 界面不要自己再排一遍：排序公式只有一处实现，agent 引用的顺序才和界面一致。
 *
 * <p>官方帖和社区帖在视觉上要能区分（来源标签），因为它们的分量不一样——
 * 官方帖是价格和版本的事实来源，社区帖只是经验分享。
 */
package com.mobilegroup20.tokentrail.ui.forum;
