/**
 * 论坛界面：News / Community 两个标签、图文发布、点赞与文字评论。
 * <b>负责人：论坛侧（汪庭栋）。</b>
 *
 * <p>新界面通过 ForumFeedRepository 读取共享帖子池和独立新闻源。
 * 使用服务端返回的游标与排序，自己的帖子也公共可读。
 *
 * <p>官方帖和社区帖在视觉上要能区分（来源标签），因为它们的分量不一样——
 * 官方帖是价格和版本的事实来源，社区帖只是经验分享。
 */
package com.mobilegroup20.tokentrail.ui.forum;
