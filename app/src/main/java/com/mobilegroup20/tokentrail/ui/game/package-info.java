/**
 * 游戏主界面：赛季信息、token 与进度条、三种资源余额、战场、发起波次、
 * 底部导航（Game / Dashboard / Forum）。<b>界面由三人共担，游戏逻辑归刘宗润。</b>
 *
 * <p>整个 App <b>直接进游戏</b>，这一屏是入口，见大纲 §7。
 *
 * <p>结构上：{@code GameViewModel} 持有赛季状态和资源余额（来自
 * {@code SeasonRepository}），布局用 ViewBinding，战场是
 * {@link com.mobilegroup20.tokentrail.game.view} 里的自定义 View。
 *
 * <p>余额标签上要能看出是估算：数字旁边给出口径入口（费率版本、时区），
 * 因为所有金额都是按公开费率估的，不等于实际账单。
 */
package com.mobilegroup20.tokentrail.ui.game;
